LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_MODULE := whisperggml

LOCAL_SRC_FILES := \
    helium314_keyboard_voice_whisper_WhisperGGML.cpp \
    src/ggml/whisper.cpp \
    src/ggml/ggml.c \
    src/ggml/ggml-alloc.c \
    src/ggml/ggml-backend.c \
    src/ggml/ggml-quants.c \
    src/jni_utils.cpp

LOCAL_C_INCLUDES := \
    $(LOCAL_PATH) \
    $(LOCAL_PATH)/src \
    $(LOCAL_PATH)/src/ggml

LOCAL_CFLAGS := -O3 -DNDEBUG -Wall -Wextra -Wno-unused-parameter -ffast-math -DFLAG_DO_PROFILE
LOCAL_CPPFLAGS := -std=c++11 -fexceptions

# For ARM NEON optimizations
ifeq ($(TARGET_ARCH), arm64-v8a)
    LOCAL_CFLAGS += -march=armv8-a+fp16
endif

LOCAL_LDLIBS := -llog -landroid -ldl

LOCAL_CLANG := true
LOCAL_SDK_VERSION := 21
LOCAL_NDK_STL_VARIANT := c++_static

include $(BUILD_SHARED_LIBRARY)