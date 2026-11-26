# R8 rules for Release (and Staging, which is built like Release). Most libraries the app uses (kotlinx.serialization,
# OkHttp, Coil, Facebook, Firebase, Stripe, Square, MapLibre, AndroidX) ship their own consumer rules; these cover
# what they don't.

# Keep file names and line numbers so crash and error reports retrace with the build's mapping.txt, without
# shipping the original source file names.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# kotlinx.serialization: the plugin generates each @Serializable class's serializer, which the app and the
# AudienceKit SDK look up through the companion's serializer() (and serializer<T>() in the SDK's graphQL()).
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault,InnerClasses,Signature
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# Credential Manager finds its Google Play services provider by name.
-if class androidx.credentials.CredentialManager
-keep class androidx.credentials.playservices.** {
  *;
}

# WorkManager (pulled in by Glance for the Tonight widget) opens its Room database by reflection: Room looks up
# WorkDatabase_Impl by name and calls its no-argument constructor. The work-runtime that Glance 1.1.1 brings has no
# rule for that constructor under R8 full mode, so a minified build crashed at launch with "Failed to create an
# instance of androidx.work.impl.WorkDatabase". Keep every Room database's constructor.
-keep class * extends androidx.room.RoomDatabase {
    <init>();
}

# WorkManager also creates the input merger named on each work request (OverwritingInputMerger by default) by
# reflection. Without its no-argument constructor Glance's work never ran, so the Tonight widget stayed on its
# loading spinner.
-keep class * extends androidx.work.InputMerger {
    <init>();
}
