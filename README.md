# minecraft-maps-to-images

Minecraft のマップアイテムを PNG 画像に変換するツールです。

## 機能

- `.dat` 形式のマップファイルを PNG 画像に変換
- ハッシュ値による差分検出（変更されたファイルのみ変換）
- Cloudflare R2 への自動アップロード（オプション）

## ビルド

### ローカルビルド

```bash
./gradlew build
```

生成される JAR: `build/libs/map-to-img.jar`

### Docker イメージビルド

```bash
docker build -t map-to-img .
```

## 実行

### ローカル実行

```bash
java -jar build/libs/map-to-img.jar /path/to/.minecraft/saves/your-world/
```

### Docker 実行

```bash
docker run --rm -v /path/to/your-world:/world map-to-img
```

Windows の場合:
```bash
docker run --rm -v C:/Users/username/AppData/Roaming/.minecraft/saves/MyWorld:/world map-to-img
```

## 出力

変換後のファイルは以下の場所に出力されます:

```
/world/
  ├── data/map_0.dat       # 元のマップファイル
  ├── out/map_0.png        # 変換された PNG
  └── map_hash/map_0.hash  # ハッシュファイル（差分検出用）
```

## Cloudflare R2 連携

環境変数を設定することで、変換した画像を R2 に自動アップロードできます:

```bash
export R2_ACCOUNT_ID="your-account-id"
export R2_ACCESS_KEY_ID="your-access-key"
export R2_SECRET_ACCESS_KEY="your-secret-key"
export R2_BUCKET_NAME="your-bucket-name"
```

Docker での R2 連携:
```bash
docker run --rm \
  -e R2_ACCOUNT_ID="your-account-id" \
  -e R2_ACCESS_KEY_ID="your-access-key" \
  -e R2_SECRET_ACCESS_KEY="your-secret-key" \
  -e R2_BUCKET_NAME="your-bucket-name" \
  -v /path/to/your-world:/world \
  map-to-img
```
