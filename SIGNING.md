# 签名配置（Release APK）

Debug APK 可直接安装测试，无需签名。若要发布 / 上架，需要打 **Release + 签名** 的 APK。

## 方式一：使用已有签名密钥（推荐）

### 1. 生成签名密钥（仅首次需要）
```bash
keytool -genkeypair -v \
  -keystore wallpaperextend.keystore \
  -storepass 你的store密码 \
  -keypass 你的key密码 \
  -alias wallpaperextend \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000
```
把生成的 `wallpaperextend.keystore` 放到项目根目录。

### 2. 创建 `app/keystore.properties`（不要提交到公开仓库）
```
storePassword=你的store密码
keyPassword=你的key密码
keyAlias=wallpaperextend
storeFile=../wallpaperextend.keystore
```

### 3. 在 `app/build.gradle` 中接入签名
在 `android { ... }` 块内加入：

```groovy
def keystoreProps = new Properties()
def propsFile = rootProject.file("app/keystore.properties")
if (propsFile.exists()) {
    keystoreProps.load(new FileInputStream(propsFile))
}

android {
    // ... 已有配置 ...

    signingConfigs {
        release {
            if (propsFile.exists()) {
                storeFile file(keystoreProps['storeFile'])
                storePassword keystoreProps['storePassword']
                keyAlias keystoreProps['keyAlias']
                keyPassword keystoreProps['keyPassword']
            }
        }
    }

    buildTypes {
        release {
            signingConfig signingConfigs.release
            // ... 其他配置
        }
    }
}
```

### 4. 打包
```bash
./build_apk.sh release
# 产物: app/build/outputs/apk/release/app-release.apk
```

---

## 方式二：让 Android Studio 代管签名

1. 菜单 **Build → Generate Signed Bundle / APK**
2. 选 **APK** → 下一步
3. 点 **Create new...** 新建密钥（或选已有）
4. 选择 `release` build variant → 完成
5. 产物在 `app/build/outputs/apk/release/`

---

## 方式三：Google Play / AAB（上架用）

若上架 Google Play，建议出 **AAB** 而非 APK：
```bash
./gradlew bundleRelease
# 产物: app/build/outputs/bundle/release/app-release.aab
```

---

## 常见问题

| 问题 | 原因 | 解决 |
|------|------|------|
| `Failed to read key` | 密码/别名错 | 核对 `keystore.properties` |
| `keystore was tampered` | storeFile 路径错 | 用相对路径 `../xxx.keystore` |
| Release 安装后闪退 | 未签名或签名不一致 | 用同一密钥重签 |
| `minifyEnabled` 后崩溃 | 混淆误删类 | 在 `proguard-rules.pro` 加 keep |

> ⚠️ **密钥一旦丢失无法找回，也无法更新同一应用**，请妥善备份 `.keystore` 文件。
