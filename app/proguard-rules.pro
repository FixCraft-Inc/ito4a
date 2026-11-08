-keep class com.fixcraft.ito.transport.nativebridge.** { *; }
# The call adapter (app/src/main/cpp/ito_call_jni.cpp) finds these classes,
# their constructors and the persistence method by name.
-keep class com.fixcraft.ito.call.CallNative { *; }
-keep class com.fixcraft.ito.call.CallFailure { *; }
-keep class com.fixcraft.ito.call.ContactRecord { *; }
-keep class com.fixcraft.ito.call.CallStatus { *; }
-keep class com.fixcraft.ito.call.ContentFrame { *; }
-keep interface com.fixcraft.ito.call.SnapshotPersistence { *; }
