#!/bin/sh
set -e

export PATH=/bin:/sbin:/usr/bin:/usr/sbin:/usr/share/bin:/usr/share/sbin:/usr/local/bin:/usr/local/sbin:/system/bin:/system/xbin
export HOME=/root

mkdir -p /dev /run /linkerconfig
for _dev in null zero random urandom; do
    [ -e "/dev/$_dev" ] || touch "/dev/$_dev"
done
: > /dev/null 2>/dev/null || true
touch /run/utmpx 2>/dev/null || true
touch /linkerconfig/ld.config.txt 2>/dev/null || true

export PIP_BREAK_SYSTEM_PACKAGES=1

if [ ! -s /etc/resolv.conf ]; then
    echo "nameserver 8.8.8.8" > /etc/resolv.conf
fi

export PS1='\[\033[01;32m\]\u@redterm\[\033[00m\]:\[\033[01;34m\]\w\[\033[00m\]\$ '

if [ "$#" -eq 0 ]; then
    . /etc/profile
    [ -f /etc/init.sh ] && . /etc/init.sh
    export PS1='\[\033[01;32m\]\u@redterm\[\033[00m\]:\[\033[01;34m\]\w\[\033[00m\]\$ '
    cd "$HOME"
    /bin/ash
else
    exec "$@"
fi