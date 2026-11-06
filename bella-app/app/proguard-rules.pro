# Bella Finance — правила R8.
# Дані зберігаються через kotlinx.serialization: серіалізатори класів застосунку мають лишитися з тими самими
# іменами полів, інакше збережений стан і резервні копії не прочитаються.
-keepattributes *Annotation*, InnerClasses, Signature, RuntimeVisibleAnnotations, AnnotationDefault
-dontnote kotlinx.serialization.**
-keepclassmembers @kotlinx.serialization.Serializable class ua.bella.finance.** {
    *** Companion;
    *;
}
-keep,includedescriptorclasses class ua.bella.finance.**$$serializer { *; }
-keepclassmembers class ua.bella.finance.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep @kotlinx.serialization.Serializable class ua.bella.finance.** { *; }
# Фонові задачі WorkManager створюються за іменем класу
-keep class ua.bella.finance.** extends androidx.work.ListenableWorker { <init>(...); }
