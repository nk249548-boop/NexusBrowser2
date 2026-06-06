# ⚡ Gradle Wrapper Setup - Quick Start

**Agar gradle-wrapper.jar setup karna hai to:**

---

## 🚀 Easiest Way (Auto-Download)

Just run this command - Gradle automatically downloads and sets up everything:

```bash
chmod +x gradlew
./gradlew clean build
```

✅ Gradle will automatically:
- Download gradle-8.4-bin.zip
- Extract gradle-wrapper.jar
- Set everything up
- Build your APK

**That's it! No manual setup needed!**

---

## Alternative: Use Setup Script

If auto-download doesn't work:

```bash
chmod +x setup-gradle-wrapper.sh
./setup-gradle-wrapper.sh

# Then build:
./gradlew clean build
```

---

## GitHub Actions (Best for Upload)

Push to GitHub and GitHub Actions will:
✅ Automatically download Gradle 8.4
✅ Build the APK
✅ Upload artifacts
✅ No setup needed!

---

**More details in: GRADLE_WRAPPER_SETUP.md**

