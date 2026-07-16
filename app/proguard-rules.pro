# Minification is OFF (isMinifyEnabled = false in app/build.gradle.kts). This file is wired in
# so the R8 posture is a deliberate decision, not an accident, whenever minification is turned on.
#
# Before the first GitHub release, someone must decide the R8 posture deliberately:
# - Room and jsoup both ship consumer ProGuard rules, so their own reflection needs should be
#   covered automatically — but that has not been verified with minification actually enabled.
# - Reflection-adjacent corners of this app (Room entities/DAOs, any KSP-generated code, Gson/
#   Moshi-style (de)serialization if added later) deserve a dedicated pass with R8 turned on
#   before shipping a minified release build.
