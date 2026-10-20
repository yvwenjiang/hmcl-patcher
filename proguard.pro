-injars      build/libs/hmcl-patcher-dev.jar
-outjars     build/libs/hmcl-patcher.jar
-libraryjars <java.home>/jmods/java.base.jmod(!**.jar;!module-info.class)
-libraryjars <java.home>/jmods/java.desktop.jmod(!**.jar;!module-info.class)

-dontshrink
-dontoptimize
-dontwarn
-dontnote
-dontusemixedcaseclassnames
-verbose

-keepattributes InnerClasses,EnclosingMethod,Signature
-keep class hmcl.patcher.Main {
    public static void main(java.lang.String[]);
}

-renamesourcefileattribute SourceFile
-printmapping build/obfuscation/mapping.txt
