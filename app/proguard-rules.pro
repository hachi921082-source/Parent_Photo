# detailed-spec 11.4 のkeepルール(R8がmailcap/DataContentHandlerを除去し multipart/mixed の DCH が見つからなくなるため必要)
-keep class javax.mail.** { *; }
-keep class com.sun.mail.** { *; }
-keep class javax.activation.** { *; }
-keep class com.sun.activation.** { *; }
-dontwarn java.awt.**
-dontwarn java.beans.**
-dontwarn javax.security.sasl.**
-dontwarn javax.naming.**
-dontwarn org.apache.harmony.**
