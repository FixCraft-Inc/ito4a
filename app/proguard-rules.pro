-keep class com.fixcraft.ito.transport.nativebridge.** { *; }
# The call adapter (app/src/main/cpp/ito_call_jni.cpp) finds these classes,
# their constructors and the persistence method by name.
-keep class com.fixcraft.ito.call.CallNative { *; }
-keep class com.fixcraft.ito.call.CallFailure { *; }
-keep class com.fixcraft.ito.call.ContactRecord { *; }
-keep class com.fixcraft.ito.call.CallStatus { *; }
-keep class com.fixcraft.ito.call.ContentFrame { *; }
# Message values and callbacks have constructors/methods resolved by JNI.
-keep class com.fixcraft.ito.messaging.MessageNative { *; }
-keep class com.fixcraft.ito.messaging.MailboxTarget { *; }
-keep class com.fixcraft.ito.messaging.SealedItem { *; }
-keep class com.fixcraft.ito.messaging.PairingCode { *; }
-keep class com.fixcraft.ito.messaging.PairingScan { *; }
-keep class com.fixcraft.ito.messaging.PairingState { *; }
-keep class com.fixcraft.ito.messaging.PairingEvent { *; }
-keep class com.fixcraft.ito.messaging.Inbox { *; }
-keep class com.fixcraft.ito.messaging.PairedContact { *; }
-keep class com.fixcraft.ito.messaging.MessageValue { *; }
-keep interface com.fixcraft.ito.messaging.MessageSink { *; }
-keep class com.fixcraft.ito.messaging.OutboxEntry { *; }
-keep interface com.fixcraft.ito.call.SnapshotPersistence { *; }
