#!/usr/bin/env bash
# Read-only checks; does not change device tree, load modules or start ROS.
set -u
printf '\n===== LINUX =====\n'; uname -r
printf '\n===== INPUT/RC =====\n'; ls -l /dev/lirc* /sys/class/rc/rc* 2>/dev/null || true
printf '\n===== DEVICE TREE IR =====\n'; find /proc/device-tree -iname '*ir*' -o -iname '*pwm*' 2>/dev/null | head -n 35
printf '\n===== KERNEL CONFIG =====\n'; if [ -r /proc/config.gz ]; then zcat /proc/config.gz | grep -E 'CONFIG_(IR|RC_CORE|RC_DEVICES|IR_PWM_TX)' | head -n 40; else echo 'config not readable'; fi
printf '\n===== DRIVERS =====\n'; lsmod | grep -E 'rc_core|ir_|pwm' || true
printf '\n===== DMESG =====\n'; dmesg 2>/dev/null | grep -Ei 'infrared|ir-receiver|rc-core|lirc' | tail -n 30 || true
printf '\nNo device-tree or motor changes were made.\n'
