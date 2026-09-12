# 确认现在的 AndroidManifest 会被 flutter create 覆盖, 所以不用手动改了
# 我们在 workflow 里会 flutter create . 重新生成完整的安卓目录
# 只需要确认 pubspec.yaml 里有 google_mlkit_text_recognition 依赖即可
print('ok')
