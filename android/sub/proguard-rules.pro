# マニフェストから参照される Activity / Service は R8 が保持するので追加の keep は不要。
# 素の View だけで Compose も reflection も使わないため、既定の proguard-android-optimize で足りる。
