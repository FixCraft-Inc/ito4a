# Copyright (C) 2026 FixCraft Inc. AGPL-3.0-or-later.
LOCAL_PATH := $(call my-dir)

ifndef ITO_YUME_SHARED_ROOT
$(error ITO_YUME_SHARED_ROOT must identify the provenance-bound public libyume output)
endif
ifndef ITO_CALL_CORE_ROOT
$(error ITO_CALL_CORE_ROOT must identify the provenance-bound call core output)
endif

include $(CLEAR_VARS)
LOCAL_MODULE := yume_shared
LOCAL_SRC_FILES := $(ITO_YUME_SHARED_ROOT)/$(TARGET_ARCH_ABI)/libyume.so
LOCAL_EXPORT_C_INCLUDES := $(ITO_YUME_SHARED_ROOT)/include
include $(PREBUILT_SHARED_LIBRARY)

# The portable call core: its own stock OpenSSL is linked statically inside
# it and hidden, so it cannot interpose on libyume's.
include $(CLEAR_VARS)
LOCAL_MODULE := itocall_shared
LOCAL_SRC_FILES := $(ITO_CALL_CORE_ROOT)/$(TARGET_ARCH_ABI)/libitocall.so
LOCAL_EXPORT_C_INCLUDES := $(ITO_CALL_CORE_ROOT)/include
include $(PREBUILT_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := itojni
LOCAL_SRC_FILES := ../cpp/ito_jni.cpp ../cpp/ito_call_jni.cpp
LOCAL_CPPFLAGS := -std=c++20 -fexceptions -fvisibility=hidden -fvisibility-inlines-hidden \
    -fstack-protector-strong -ffunction-sections -fdata-sections -Wall -Wextra -Werror
LOCAL_SHARED_LIBRARIES := yume_shared itocall_shared
LOCAL_LDFLAGS := -Wl,--gc-sections -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384
include $(BUILD_SHARED_LIBRARY)
