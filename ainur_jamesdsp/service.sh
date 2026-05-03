ADBBASE="${NVBASE:-/data/adb}"
LOGFILE="$MODPATH/jdsp_mount.log"
[ -d "$ADBBASE/aml/$MODID" ] && DIR="$ADBBASE/aml/$MODID" || DIR="$MODDIR" # AML Workaround

logm() {
  echo "[$(date +%F_%T)] $1" >> "$LOGFILE"
}

resolve_dir() {
  local P="$1" RP=""
  RP="$(readlink -f "$P" 2>/dev/null)"
  [ -n "$RP" ] && echo "$RP" || echo "$P"
}

mount_jdsp_overlay_dir() {
  local UPPER="$1" LOWER="$2" TAG="$3"
  local TARGET="$(resolve_dir "$LOWER")"
  [ -d "$UPPER" ] || { logm "skip $TAG: upper missing $UPPER"; return 0; }
  [ -d "$TARGET" ] || { logm "skip $TAG: lower missing $TARGET"; return 0; }
  [ -f "$UPPER/libjamesdsp.so" ] || { logm "skip $TAG: upper lib missing"; return 0; }
  [ -f "$TARGET/libjamesdsp.so" ] && { logm "ok $TAG: already visible at $TARGET"; return 0; }
  grep -q " $TARGET overlay " /proc/mounts 2>/dev/null && { logm "ok $TAG: overlay already mounted"; return 0; }
  local WORKROOT="$ADBBASE/modules/.$MODID-libovl/$TAG"
  rm -rf "$WORKROOT" 2>/dev/null
  mkdir -p "$WORKROOT/work" 2>/dev/null
  if mount -t overlay overlay -o "lowerdir=$TARGET,upperdir=$UPPER,workdir=$WORKROOT/work" "$TARGET" 2>/dev/null; then
    [ -f "$TARGET/libjamesdsp.so" ] && logm "mounted $TAG on $TARGET" || logm "mounted $TAG but lib still hidden"
  else
    logm "mount failed $TAG target=$TARGET"
  fi
}

patch_jdsp_conf_path() {
  local NEWPATH="$1" NEWNAME=""
  local CFILE XFILE TMPF
  NEWNAME="$(basename "$NEWPATH")"
  for CFILE in $(find "$DIR/system/vendor" "$DIR/vendor" "$DIR/system/vendor/vendor" -type f -name "*audio_effects*.conf" 2>/dev/null); do
    [ -f "$CFILE" ] || continue
    TMPF="$CFILE.tmp.jdsp"
    awk -v np="$NEWPATH" '
      BEGIN { in_jdsp = 0 }
      {
        line = $0
        if (!in_jdsp && line ~ /^[[:space:]]*jdsp[[:space:]]*\{/) {
          gsub(/path[[:space:]]+[^[:space:]}]+/, "path " np, line)
          print line
          if (line ~ /\}/) {
            in_jdsp = 0
          } else {
            in_jdsp = 1
          }
          next
        }
        if (in_jdsp) {
          if (line ~ /^[[:space:]]*path[[:space:]]+/) {
            sub(/^[[:space:]]*path[[:space:]]+.*/, "    path " np, line)
          }
          print line
          if (line ~ /\}/) {
            in_jdsp = 0
          }
          next
        }
        print line
      }
    ' "$CFILE" > "$TMPF"
    if [ -s "$TMPF" ]; then
      mv -f "$TMPF" "$CFILE"
    else
      rm -f "$TMPF"
      logm "patch write failed in $CFILE"
      continue
    fi
    if awk -v np="$NEWPATH" '
      BEGIN { in_jdsp = 0; ok = 0 }
      {
        if (!in_jdsp && $0 ~ /^[[:space:]]*jdsp[[:space:]]*\{/) {
          in_jdsp = 1
        }
        if (in_jdsp && $0 ~ /path[[:space:]]+/ && index($0, np) > 0) {
          ok = 1
        }
        if (in_jdsp && $0 ~ /\}/) {
          in_jdsp = 0
        }
      }
      END { exit(ok ? 0 : 1) }
    ' "$CFILE"; then
      logm "patched jdsp path in $CFILE -> $NEWPATH"
    else
      logm "patch check failed in $CFILE for $NEWPATH"
    fi
  done
  for XFILE in $(find "$DIR/system/vendor" "$DIR/vendor" "$DIR/system/vendor/vendor" -type f -name "*audio_effects*.xml" 2>/dev/null); do
    [ -f "$XFILE" ] || continue
    sed -ri "/<library[^>]*name=\"jdsp\"/ s|path=\"[^\"]*\"|path=\"$NEWNAME\"|" "$XFILE"
  done
}

bind_jdsp_proxy_file() {
  local UPPERLIB="$1" PROXYFILE="$2" TAG="$3"
  [ -f "$UPPERLIB" ] || { logm "skip $TAG: upper lib missing $UPPERLIB"; return 1; }
  [ -f "$PROXYFILE" ] || { logm "skip $TAG: proxy missing $PROXYFILE"; return 1; }
  chcon --reference="$PROXYFILE" "$UPPERLIB" 2>/dev/null || true
  if mount -o bind "$UPPERLIB" "$PROXYFILE" 2>/dev/null; then
    logm "bind proxy ok $TAG: $UPPERLIB -> $PROXYFILE"
    return 0
  fi
  logm "bind proxy failed $TAG: $UPPERLIB -> $PROXYFILE"
  return 1
}

pick_proxy_file() {
  for CAND in "$@"; do
    [ -f "$CAND" ] && { echo "$CAND"; return 0; }
  done
  return 1
}

rebind_cfg_after_patch() {
  local i j j2 j3
  for i in $(find "$DIR/vendor" -type f \( -name "*audio_effects*.conf" -o -name "*audio_effects*.xml" \) 2>/dev/null); do
    j="$(echo "$i" | sed "s|$DIR||")"
    bind_if_exists "$i" "$j" "cfg-rebind"
    case "$j" in
      /vendor/*)
        j3="/vendor/vendor${j#/vendor}"
        bind_if_exists "$i" "$j3" "cfg-rebind"
        ;;
    esac
  done
  for i in $(find "$DIR/system/vendor" -type f \( -name "*audio_effects*.conf" -o -name "*audio_effects*.xml" \) 2>/dev/null); do
    j="$(echo "$i" | sed "s|$DIR/system||")"
    bind_if_exists "$i" "$j" "cfg-rebind"
    j2="$(echo "$i" | sed "s|$DIR||")"
    bind_if_exists "$i" "$j2" "cfg-rebind"
    case "$j" in
      /vendor/*)
        j3="/vendor/vendor${j#/vendor}"
        bind_if_exists "$i" "$j3" "cfg-rebind"
        ;;
    esac
  done
}

bind_if_exists() {
  local SRC="$1" DST="$2" TAG="$3"
  [ -f "$SRC" ] || { logm "bind skip $TAG: source missing $SRC"; return 1; }
  [ -e "$DST" ] || { logm "bind skip $TAG: target missing $DST"; return 1; }
  chcon --reference="$DST" "$SRC" 2>/dev/null || true
  if mount -o bind "$SRC" "$DST" 2>/dev/null; then
    logm "bind ok $TAG: $SRC -> $DST"
    return 0
  fi
  logm "bind failed $TAG: $SRC -> $DST"
  return 1
}

logm "service start dir=$DIR"
for PART in vendor odm product system_ext; do
  [ -d "$DIR/system/$PART" ] || continue
  for i in $(find "$DIR/system/$PART" -type f \( -name "*audio_effects*.conf" -o -name "*audio_effects*.xml" \)); do
    j="$(echo "$i" | sed "s|$DIR/system||")"
    bind_if_exists "$i" "$j" "cfg"
    if [ "$PART" = "vendor" ]; then
      j2="$(echo "$i" | sed "s|$DIR||")"
      bind_if_exists "$i" "$j2" "cfg"
      case "$j" in
        /vendor/*)
          j3="/vendor/vendor${j#/vendor}"
          bind_if_exists "$i" "$j3" "cfg"
          ;;
      esac
    fi
  done
done

[ -d "$DIR/vendor" ] && for i in $(find "$DIR/vendor" -type f \( -name "*audio_effects*.conf" -o -name "*audio_effects*.xml" \)); do
  j="$(echo "$i" | sed "s|$DIR||")"
  bind_if_exists "$i" "$j" "cfg"
  case "$j" in
    /vendor/*)
      j3="/vendor/vendor${j#/vendor}"
      bind_if_exists "$i" "$j3" "cfg"
      ;;
  esac
done

# Fallback for KSU/Android 16 devices where new soundfx files are not visible in runtime mounts.
mount_jdsp_overlay_dir "$DIR/system/vendor/lib/soundfx" "/vendor/lib/soundfx" "vendor_lib"
mount_jdsp_overlay_dir "$DIR/system/vendor/lib64/soundfx" "/vendor/lib64/soundfx" "vendor_lib64"
mount_jdsp_overlay_dir "$DIR/system/lib/soundfx" "/system/lib/soundfx" "system_lib"
mount_jdsp_overlay_dir "$DIR/system/lib64/soundfx" "/system/lib64/soundfx" "system_lib64"

# If overlay mount is blocked by kernel policy, bind over an existing vendor library file.
PROXY64_OK=0
IS64_RUNTIME=0
[ -d "/vendor/lib64/soundfx" ] && IS64_RUNTIME=1

if [ ! -f "/vendor/lib64/soundfx/libjamesdsp.so" ]; then
  UPPER64="$DIR/system/vendor/lib64/soundfx/libjamesdsp.so"
  PROXY64="$(pick_proxy_file \
    /vendor/vendor/lib64/soundfx/libqcomvisualizer.so \
    /vendor/vendor/lib64/soundfx/libvisualizer.so \
    /vendor/vendor/lib64/soundfx/libvolumelistener.so \
    /vendor/vendor/lib64/soundfx/libeffectproxy.so \
    /vendor/vendor/lib64/soundfx/libswdap.so \
    /vendor/lib64/soundfx/libqcomvisualizer.so \
    /vendor/lib64/soundfx/libvisualizer.so \
    /vendor/lib64/soundfx/libvolumelistener.so \
    /vendor/lib64/soundfx/libeffectproxy.so \
    /vendor/lib64/soundfx/libswdap.so)"
  if [ -n "$PROXY64" ] && bind_jdsp_proxy_file "$UPPER64" "$PROXY64" "proxy64"; then
    PROXY64_OK=1
    patch_jdsp_conf_path "$PROXY64"
    rebind_cfg_after_patch
  else
    logm "proxy64 unavailable"
  fi
fi

if [ "$IS64_RUNTIME" -eq 0 ] && [ ! -f "/vendor/lib/soundfx/libjamesdsp.so" ] && [ "$PROXY64_OK" -eq 0 ]; then
  UPPER32="$DIR/system/vendor/lib/soundfx/libjamesdsp.so"
  PROXY32="$(pick_proxy_file \
    /vendor/vendor/lib/soundfx/libqcomvisualizer.so \
    /vendor/vendor/lib/soundfx/libvisualizer.so \
    /vendor/vendor/lib/soundfx/libvolumelistener.so \
    /vendor/vendor/lib/soundfx/libeffectproxy.so \
    /vendor/vendor/lib/soundfx/libbundlewrapper.so \
    /vendor/lib/soundfx/libqcomvisualizer.so \
    /vendor/lib/soundfx/libvisualizer.so \
    /vendor/lib/soundfx/libvolumelistener.so \
    /vendor/lib/soundfx/libeffectproxy.so \
    /vendor/lib/soundfx/libbundlewrapper.so)"
  if [ -n "$PROXY32" ] && bind_jdsp_proxy_file "$UPPER32" "$PROXY32" "proxy32"; then
    patch_jdsp_conf_path "$PROXY32"
    rebind_cfg_after_patch
  else
    logm "proxy32 unavailable"
  fi
fi
killall -q audioserver
logm "audioserver restart requested"

# Install APK once boot is complete (fallback when boot-completed.sh is not triggered)
(
  until [ "$(getprop sys.boot_completed)" = "1" ]; do sleep 3; done
  sleep 5
  APP=$(pm list packages -3 | grep "^package:james\.dsp$")
  if [ -z "$APP" ] && [ ! -f "$MODPATH/disable" ] && [ -f "$MODPATH/JamesDSPManager.apk" ]; then
    pm install "$MODPATH/JamesDSPManager.apk"
    sleep 1
    pm disable james.dsp 2>/dev/null
    pm enable james.dsp 2>/dev/null
  fi
)&
