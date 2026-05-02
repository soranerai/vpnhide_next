obj-m += vpnhide_kmod.o

ifeq ($(KERNELRELEASE),)

# KERNEL_SRC and CLANG_DIR come from the environment (set via .env + direnv).
ifndef KERNEL_SRC
$(error KERNEL_SRC is not set — copy .env.example to .env and fill in paths)
endif
ifndef CLANG_DIR
$(error CLANG_DIR is not set — copy .env.example to .env and fill in paths)
endif

ARCH           := arm64
CROSS_COMPILE  := aarch64-linux-gnu-
CC             := $(CLANG_DIR)/clang
LD             := $(CLANG_DIR)/ld.lld
AR             := $(CLANG_DIR)/llvm-ar
NM             := $(CLANG_DIR)/llvm-nm
OBJCOPY        := $(CLANG_DIR)/llvm-objcopy
OBJDUMP        := $(CLANG_DIR)/llvm-objdump
STRIP          := $(CLANG_DIR)/llvm-strip

MAKE_ARGS := -C $(KERNEL_SRC) M=$(CURDIR) \
	ARCH=$(ARCH) LLVM=1 LLVM_IAS=1 \
	CROSS_COMPILE=$(CROSS_COMPILE) \
	CC=$(CC) LD=$(LD) AR=$(AR) NM=$(NM) \
	OBJCOPY=$(OBJCOPY) OBJDUMP=$(OBJDUMP) STRIP=$(STRIP)

all:
	$(MAKE) $(MAKE_ARGS) modules

KP_DIR := $(CURDIR)/../kernels/KernelPatch

# KPM build flags
KPM_INCLUDE_DIRS := . include patch/include
KPM_INCLUDE_FLAGS := $(foreach dir,$(KPM_INCLUDE_DIRS),-I$(KP_DIR)/kernel/$(dir))
# No extra kernel include flags for stable mock build
KPM_INCLUDE_FLAGS += -I$(KERNEL_SRC)/include/generated \
                     -I$(KERNEL_SRC)/include/generated/uapi

kpm:
	$(CC) -target aarch64-linux-android \
		-O3 -fno-pic -fno-pie -fno-plt \
		-fvisibility=hidden -mno-outline-atomics \
		-mcmodel=small \
		-fno-stack-protector -fno-common -fno-builtin \
		-ffreestanding -fno-asynchronous-unwind-tables -fno-unwind-tables \
		-fno-stack-check -fno-stack-protector \
		-nostdinc \
		$(KPM_INCLUDE_FLAGS) \
		-I$(CURDIR)/generated \
		-D__KERNEL__ -DMODNAME=\"vpnhide\" \
		-DCONFIG_ARM64 \
		-r \
		-o vpnhide.kpm vpnhide_kpm.c

strip: all
	$(STRIP) -d $(CURDIR)/vpnhide_kmod.ko

clean:
	$(MAKE) $(MAKE_ARGS) clean

endif
