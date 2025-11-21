import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import se.llbit.nbt.CompoundTag;
import se.llbit.nbt.NamedTag;
import se.llbit.nbt.Tag;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

public class Main {
    private static S3Client s3Client;
    private static String bucketName;

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.out.println("Usage: java -jar map-to-img.jar [directory]");
            System.out.println("Application will search up to 3 levels deep in given directory, so it can be a world or " +
                    "just the .minecraft/saves folder.");
            System.out.println();
            System.out.println("Environment variables for R2 upload:");
            System.out.println("  R2_ACCOUNT_ID    - Cloudflare account ID");
            System.out.println("  R2_ACCESS_KEY_ID - R2 access key ID");
            System.out.println("  R2_SECRET_ACCESS_KEY - R2 secret access key");
            System.out.println("  R2_BUCKET_NAME   - R2 bucket name");
            System.exit(1);
        }
        String folder = args[0];

        // Initialize R2 client if credentials are provided
        initR2Client();

        List<Path> mapFiles = Files.find(Paths.get(folder), 3, (path, attr) -> {
            return path.getFileName().toString().matches("map_[0-9]+\\.dat");
        }).collect(Collectors.toList());

        Path out = Paths.get(folder, "out");
        Path hashDir = Paths.get(folder, "map_hash");
        Files.createDirectories(out);
        Files.createDirectories(hashDir);
        System.out.println("Writing files to " + out);

        int num = 0;
        int skipped = 0;
        for (Path p : mapFiles) {
            try {
                // Calculate hash of .dat file
                String currentHash = calculateFileHash(p);
                String mapName = p.getFileName().toString().replace(".dat", "");
                Path hashFile = hashDir.resolve(mapName + ".hash");

                // Check if hash has changed
                if (Files.exists(hashFile)) {
                    String storedHash = Files.readString(hashFile).trim();
                    if (storedHash.equals(currentHash)) {
                        skipped++;
                        continue; // Skip unchanged file
                    }
                }

                BufferedImage img = new BufferedImage(128, 128, BufferedImage.TYPE_4BYTE_ABGR);
                CompoundTag t = read(p.toFile()).unpack().get("data").asCompound();
                byte[] data = t.get("colors").byteArray();

                for (int x = 0; x < 128; x++) {
                    for (int y = 0; y < 128; y++) {
                        byte input = data[x + y * 128];
                        int colId = (input >>> 2) & 0b111111;
                        byte shader = (byte) (input & 0b11);

                        BasicColor col = BasicColor.colors.get(colId);
                        if (col == null) {
                            System.out.println("Unknown color: " + colId);
                            col = BasicColor.TRANSPARENT;
                        }
                        img.setRGB(x, y, col.shaded(shader));
                    }
                }

                // write image to temp location
                String pngFileName = mapName + ".png";
                Path outFile = out.resolve(pngFileName);
                Path tempFile = out.resolve("temp");
                ImageIO.write(img, "png", tempFile.toFile());

                // Move temp file to output
                Files.move(tempFile, outFile, StandardCopyOption.REPLACE_EXISTING);

                // Upload to R2 if configured
                if (s3Client != null) {
                    uploadToR2(outFile, pngFileName);
                }

                // Save hash after successful conversion and upload
                Files.writeString(hashFile, currentHash);

                num++;
                System.out.println("Converted: " + mapName);
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }
        System.out.println("Done! Converted " + num + " maps, skipped " + skipped + " unchanged.");

        if (s3Client != null) {
            s3Client.close();
        }
    }

    private static void initR2Client() {
        String accountId = System.getenv("R2_ACCOUNT_ID");
        String accessKeyId = System.getenv("R2_ACCESS_KEY_ID");
        String secretAccessKey = System.getenv("R2_SECRET_ACCESS_KEY");
        bucketName = System.getenv("R2_BUCKET_NAME");

        if (accountId == null || accessKeyId == null || secretAccessKey == null || bucketName == null) {
            System.out.println("R2 credentials not configured. Files will only be saved locally.");
            return;
        }

        String endpoint = String.format("https://%s.r2.cloudflarestorage.com", accountId);

        s3Client = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKeyId, secretAccessKey)))
                .region(Region.of("auto"))
                .build();

        System.out.println("R2 client initialized. Bucket: " + bucketName);
    }

    private static void uploadToR2(Path file, String key) {
        try {
            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .contentType("image/png")
                    .build();

            s3Client.putObject(putRequest, file);
            System.out.println("Uploaded to R2: " + key);
        } catch (Exception e) {
            System.err.println("Failed to upload to R2: " + key);
            e.printStackTrace();
        }
    }

    private static String calculateFileHash(Path file) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] fileBytes = Files.readAllBytes(file);
        byte[] hashBytes = digest.digest(fileBytes);

        StringBuilder sb = new StringBuilder();
        for (byte b : hashBytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    public static Tag read(File f) throws IOException {
        InputStream input = new FileInputStream(f);
        byte[] fileContent = IOUtils.toByteArray(input);
        return NamedTag.read(
                new DataInputStream(new ByteArrayInputStream(gzipDecompress(fileContent)))
        );
    }

    // Source: https://stackoverflow.com/a/44922240
    public static byte[] gzipDecompress(byte[] compressedData) {
        byte[] result = new byte[]{};
        try (ByteArrayInputStream bis = new ByteArrayInputStream(compressedData);
             ByteArrayOutputStream bos = new ByteArrayOutputStream();
             GZIPInputStream gzipIS = new GZIPInputStream(bis)) {
            byte[] buffer = new byte[1024];
            int len;
            while ((len = gzipIS.read(buffer)) != -1) {
                bos.write(buffer, 0, len);
            }
            result = bos.toByteArray();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return result;
    }
}
