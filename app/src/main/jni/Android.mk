# Copyright (C) 2026 FixCraft Inc. AGPL-3.0-or-later.
LOCAL_PATH := $(call my-dir)

ifndef ITO_YUME_SHARED_ROOT
$(error ITO_YUME_SHARED_ROOT must identify the provenance-bound public libyume output)
endif

include $(CLEAR_VARS)
LOCAL_MODULE := yume_shared
LOCAL_SRC_FILES := $(ITO_YUME_SHARED_ROOT)/$(TARGET_ARCH_ABI)/libyume.so
LOCAL_EXPORT_C_INCLUDES := $(ITO_YUME_SHARED_ROOT)/include
include $(PREBUILT_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := itojni
LOCAL_SRC_FILES := ../cpp/ito_jni.cpp
LOCAL_CPPFLAGS := -std=c++20 -fexceptions -fvisibility=hidden -fvisibility-inlines-hidden \
    -fstack-protector-strong -ffunction-sections -fdata-sections -Wall -Wextra -Werror
LOCAL_SHARED_LIBRARIES := yume_shared
LOCAL_LDFLAGS := -Wl,--gc-sections -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384
include $(BUILD_SHARED_LIBRARY)
