

# Gson reads these through reflection (API answers and the disk cache): names and fields must survive
-keepattributes Signature,*Annotation*,InnerClasses,EnclosingMethod
-keep class com.ledger.app.data.** { *; }
# WorkManager / services / receivers are created from their class names
-keep class com.ledger.app.capture.** { *; }
-dontwarn org.slf4j.**
# ViewModels are created by reflection (their constructors must stay)
-keep class * extends androidx.lifecycle.ViewModel { <init>(...); }
-keep class * extends androidx.lifecycle.AndroidViewModel { <init>(...); }
-keep class com.ledger.app.viewmodel.** { *; }
# Gson TypeToken subclasses (DiskCache.get<T>) need their generic signature
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-keep class com.google.gson.** { *; }
