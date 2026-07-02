# 消费者 ProGuard 规则
-keep class com.ecommerce.core.** { *; }
-keepclassmembers,allowobfuscation class * {
  @kotlinx.serialization.SerialName <fields>;
}
-keep,includedescriptorclasses class com.ecommerce.core.model.**$$serializer { *; }
-keepclassmembers class com.ecommerce.core.model.** {
    *** Companion;
}
-keepclasseswithmembers class com.ecommerce.core.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}
