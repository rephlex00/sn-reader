# Minification is ON (isMinifyEnabled = true, isShrinkResources = true in app/build.gradle.kts).
# The rules below were arrived at by auditing every reflective call site and every XML-driven
# class-name lookup in the app: each rule that follows exists because a specific lookup below
# needed it, and is commented in place with what it protects and why it's needed.

# --- Room (dev.reader.data) ---------------------------------------------------------------------
# Room.databaseBuilder(..., LibraryDatabase::class.java, ...) resolves the generated
# `LibraryDatabase_Impl` by Class.forName("dev.reader.data.LibraryDatabase_Impl") at runtime (this
# app does not opt into Room's KMP `@ConstructedBy` path, so the classic reflective lookup is the
# one in play). Room's own room-runtime consumer rule already covers the strict minimum
# (`-keep class * extends androidx.room.RoomDatabase { void <init>(); }`, which matches
# LibraryDatabase_Impl transitively) — this rule is deliberate belt-and-suspenders over the whole
# small entities/DAOs/migrations package, since a wrong guess here means a corrupted or
# unopenable library.db on the owner's device, not a cosmetic bug.
-keep class dev.reader.data.** { *; }

# --- E-ink integration (EinkController) ----------------------------------------------------------
# EinkController (app/src/main/kotlin/dev/reader/ui/EinkController.kt) reaches the vendor
# `android.os.EinkManager` via `context.getSystemService("eink")` and then
# `manager.javaClass.getMethod("screenRefresh"/"setScreenMode", ...)`. No rule is needed for this:
# `android.os.EinkManager` is a hidden vendor class that is never imported or referenced as a
# static type anywhere in this codebase (the handle is typed `Any?` throughout), so it is not part
# of the app's compiled program at all — R8 has no class node to strip or rename, and a `-keep`
# naming it would match nothing. The method-name string literals ("screenRefresh",
# "setScreenMode") are String constants, which R8 never rewrites. EinkController itself is kept by
# ordinary reachability (it is constructed from ReaderActivity), and every failure path here is
# already a caught `Throwable` that degrades to unavailable, so even a hypothetical miss is
# non-fatal to the reader.

# --- Custom Views inflated from XML by fully-qualified class name --------------------------------
# LayoutInflater resolves these by Class.forName(name) + the (Context, AttributeSet) constructor
# when it hits their tags in overlay_reader.xml (ChapterScrubberView, ToggleSwitchView) and
# item_toc_entry.xml (LeaderDotsView). AGP's current default proguard-android-optimize.txt does
# NOT carry the classic `-keep public class * extends android.view.View { <init>...}` rule — it
# only preserves View setters/getters for animations (see proguard-common.txt bundled in the AGP
# jar) — so without an explicit rule here R8 is free to rename these classes or strip the
# (Context, AttributeSet) constructor as "unreachable," and inflation would throw
# ClassNotFoundException/NoSuchMethodException on first open. PageView and ComicPageView are NOT
# in this list: both are constructed directly in Kotlin (`PageView(this)`, `ComicPageView(this)`),
# never inflated from XML, so ordinary reachability already protects them.
-keep class dev.reader.ui.ChapterScrubberView {
    public <init>(android.content.Context, android.util.AttributeSet);
}
-keep class dev.reader.ui.LeaderDotsView {
    public <init>(android.content.Context, android.util.AttributeSet);
}
-keep class dev.reader.ui.ToggleSwitchView {
    public <init>(android.content.Context, android.util.AttributeSet);
}

# --- Audited and found to need nothing --------------------------------------------------------
# - No Parcelable implementations anywhere in the codebase (grep for CREATOR/Parcelable/Parcelize
#   was empty) — proguard-android-optimize.txt's default Parcelable CREATOR rule is unused but
#   harmless.
# - Every enum (ViewMode, SortOrder, BookStatus, StatusFilter, TapZone, ChapterScrubberView's
#   private GestureState) is read back from SharedPreferences (where applicable) via
#   `EnumType.entries.firstOrNull { it.name == storedString }`, never `Enum.valueOf(Class, name)`
#   reflectively — the default `-keepclassmembers enum * { values(); valueOf(...); }` rule in
#   proguard-android-optimize.txt covers this pattern regardless, and enum constant `.name` values
#   are compile-time literals R8 does not rewrite.
# - No @Keep annotations existed before this pass; none were needed given the above.
