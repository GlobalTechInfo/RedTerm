#!/system/bin/sh

ARGS="-0 -L --link2symlink --sysvipc --kill-on-exit"
ARGS="$ARGS -r $ROOTFS"
ARGS="$ARGS -w /root"

for m in /apex /odm /product /system /system_ext /vendor \
 /linkerconfig/ld.config.txt \
 /linkerconfig/com.android.art/ld.config.txt \
 /plat_property_contexts /property_contexts; do
 if [ -e "$m" ]; then
  m=$(realpath "$m" 2>/dev/null || echo "$m")
  ARGS="$ARGS -b $m"
 fi
done

ARGS="$ARGS -b /sdcard"
ARGS="$ARGS -b /storage"
ARGS="$ARGS -b /dev"
ARGS="$ARGS -b /data"
ARGS="$ARGS -b /dev/urandom:/dev/random"
ARGS="$ARGS -b /proc"
ARGS="$ARGS -b /sys"

if [ -e "/proc/self/fd" ]; then
 ARGS="$ARGS -b /proc/self/fd:/dev/fd"
fi
if [ -e "/proc/self/fd/0" ]; then
 ARGS="$ARGS -b /proc/self/fd/0:/dev/stdin"
fi
if [ -e "/proc/self/fd/1" ]; then
 ARGS="$ARGS -b /proc/self/fd/1:/dev/stdout"
fi
if [ -e "/proc/self/fd/2" ]; then
 ARGS="$ARGS -b /proc/self/fd/2:/dev/stderr"
fi

if [ -n "$FAKEUID" ] && [ -e "$FAKEUID" ]; then
 ARGS="$ARGS -b $FAKEUID:/lib/libfakeuid.so"
fi

if [ -d "$PROOT_TMP_DIR" ]; then
 ARGS="$ARGS -b $PROOT_TMP_DIR"
fi

if [ -n "$PROC_SYNTHETIC" ]; then
 ARGS="$ARGS -b $PROC_SYNTHETIC/stat:/proc/stat"
 ARGS="$ARGS -b $PROC_SYNTHETIC/vmstat:/proc/vmstat"
fi

GUEST_INIT=$ROOTFS/etc/profile.d/redterm-init.sh
if [ -f "$GUEST_INIT" ]; then
  exec $PROOT $ARGS /bin/sh /etc/profile.d/redterm-init.sh
else
  exec $PROOT $ARGS $SHELL --login
fi
