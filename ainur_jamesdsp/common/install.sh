osp_detect() {
  case $1 in
        *.conf) SPACES=$(sed -n "/^output_session_processing {/,/^}/ {/^ *music {/p}" $1 | sed -r "s/( *).*/\1/")
          EFFECTS=$(sed -n "/^output_session_processing {/,/^}/ {/^${SPACES}music {/,/^${SPACES}}/p}" $1 | grep -E "^$SPACES +[A-Za-z]+" | sed -r "s/( *.*) .*/\1/g")
            for EFFECT in ${EFFECTS}; do
              SPACES=$(sed -n "/^effects {/,/^}/ {/^ *$EFFECT {/p}" $1 | sed -r "s/( *).*/\1/")
              [ "$EFFECT" != "atmos" ] && sed -i "/^effects {/,/^}/ {/^$SPACES$EFFECT {/,/^$SPACES}/ s/^/#/g}" $1
            done;;
     *.xml) EFFECTS=$(sed -n "/^ *<postprocess>$/,/^ *<\/postprocess>$/ {/^ *<stream type=\"music\">$/,/^ *<\/stream>$/ {/<stream type=\"music\">/d; /<\/stream>/d; s/<apply effect=\"//g; s/\"\/>//g; p}}" $1)
            for EFFECT in ${EFFECTS}; do
              [ "$EFFECT" != "atmos" ] && sed -ri "/^( *)<apply effect=\"$EFFECT\"\/>/d" $1
            done;;
  esac
}

uninstall_app() {
  local FILE=".apk$1" VER=$2
  [ -z $INSVER ] && return 0
  if [ -f $NVBASE/modules/$MODID/$FILE ]; then
    [ $INSVER -lt $VER ] && pm uninstall -k james.dsp
  else
    pm uninstall james.dsp
  fi
}

map_cfg_target() {
  local FILE="$1"
  FILE="$(echo "$FILE" | sed -e "s|^$MODPATH/vendor|$MODPATH/system/vendor|g" -e "s|^$MODPATH/odm|$MODPATH/system/odm|g" -e "s|^$MODPATH/product|$MODPATH/system/product|g" -e "s|^$MODPATH/system_ext|$MODPATH/system/system_ext|g")"
  echo "$FILE"
}

cfg_source_path() {
  local OFILE="$1" SRC=""
  [ -f "$ORIGDIR$OFILE" ] && SRC="$ORIGDIR$OFILE"
  [ -z "$SRC" ] && [ -f "$OFILE" ] && SRC="$OFILE"
  if [ -z "$SRC" ]; then
    case "$OFILE" in
      /vendor/*)
        [ -f "$ORIGDIR/system$OFILE" ] && SRC="$ORIGDIR/system$OFILE"
        [ -z "$SRC" ] && [ -f "/system$OFILE" ] && SRC="/system$OFILE"
        ;;
      /system/vendor/*)
        [ -f "$ORIGDIR${OFILE#/system}" ] && SRC="$ORIGDIR${OFILE#/system}"
        [ -z "$SRC" ] && [ -f "${OFILE#/system}" ] && SRC="${OFILE#/system}"
        ;;
    esac
  fi
  echo "$SRC"
}

patch_audio_cfg_file() {
  local FILE="$1"
  local LIBPATHPREFIX="$LIBPATCH"
  local FILELIBDIR="$LIBSFXDIR"
  local JDSP_CONF_PATH=""
  local JDSP_XML_LIB="libjamesdsp.so"
  case "$FILE" in
    *"/vendor/vendor/"*) LIBPATHPREFIX="\/vendor\/vendor";;
  esac
  if grep -q '/lib64/soundfx/' "$FILE" 2>/dev/null; then
    FILELIBDIR="lib64"
  elif grep -q '/lib/soundfx/' "$FILE" 2>/dev/null; then
    FILELIBDIR="lib"
  fi
  if $KSU && [ -n "$QARCH64" ]; then
    FILELIBDIR="lib64"
    JDSP_CONF_PATH="\/vendor\/lib64\/soundfx\/libqcomvisualizer.so"
    JDSP_XML_LIB="libqcomvisualizer.so"
  else
    JDSP_CONF_PATH="$LIBPATHPREFIX\/$FILELIBDIR\/soundfx\/libjamesdsp.so"
  fi
  osp_detect $FILE
  case $FILE in
    *.conf) sed -ri "/^[[:space:]]*jamesdsp[[:space:]]*\{/,/^[[:space:]]*\}/d" $FILE
            sed -ri "/^[[:space:]]*jdsp[[:space:]]*\{/,/^[[:space:]]*\}/d" $FILE
            sed -ri "/^[[:space:]]*effects[[:space:]]*\{/a\
  jamesdsp {\
    library jdsp\
    uuid f27317f4-c984-4de6-9a90-545759495bf2\
  }" $FILE
            sed -ri "/^[[:space:]]*libraries[[:space:]]*\{/a\
  jdsp {\
    path $JDSP_CONF_PATH\
  }" $FILE;;
    *.xml) sed -ri "/<effect[^>]*name=\"jamesdsp\"[^>]*>/d" $FILE
           sed -ri "/<library[^>]*name=\"jdsp\"[^>]*>/d" $FILE
           sed -ri "/<[[:space:]]*libraries([[:space:]][^>]*)?>/a\
        <library name=\"jdsp\" path=\"$JDSP_XML_LIB\"\/>" $FILE
           sed -ri "/<[[:space:]]*effects([[:space:]][^>]*)?>/a\
        <effect name=\"jamesdsp\" library=\"jdsp\" uuid=\"f27317f4-c984-4de6-9a90-545759495bf2\"\/>" $FILE;;
  esac
}

# Tell user aml is needed if applicable
FILES=$(find $NVBASE/modules/*/system $MODULEROOT/*/system -type f \( -name "*audio_effects*.conf" -o -name "*audio_effects*.xml" \) 2>/dev/null | sed "/$MODID/d")
if [ ! -z "$FILES" ] && [ ! "$(echo $FILES | grep '/aml/')" ]; then
  ui_print " "
  ui_print "   ! Conflicting audio mod found!"
  ui_print "   ! You will need to install !"
  ui_print "   ! Audio Modification Library !"
  sleep 3
fi

#Lib detection
AFDUMP="$(dumpsys media.audio_flinger 2>/dev/null)"
echo "$AFDUMP" | grep -q '/lib64/soundfx/' && HAS_LIB64=1 || HAS_LIB64=0
echo "$AFDUMP" | grep -q '/lib/soundfx/' && HAS_LIB32=1 || HAS_LIB32=0
[ "$HAS_LIB64" -eq 0 ] && [ -n "$(getprop ro.product.cpu.abilist64 2>/dev/null)" ] && HAS_LIB64=1
QARCH32=$ARCH32
QARCH64=""
LIBSFXDIR="lib"
ui_print " "
ui_print "- Lib bit detection -"
if [ "$HAS_LIB64" -eq 1 ]; then
  QARCH64="huawei"
  LIBSFXDIR="lib64"
  ui_print "   64 bit audio libs detected/preferred  "
elif [ "$HAS_LIB32" -eq 1 ]; then
  ui_print "   32 bit audio libs detected  "
else
  ui_print "   Unable to detect audio lib bit, defaulting to 32-bit  "
  LIBSFXDIR="lib"
fi
[ -f "$MODPATH/common/files/$QARCH32/libjamesdsp.so" ] && cp_ch $MODPATH/common/files/$QARCH32/libjamesdsp.so $MODPATH/system/lib/soundfx/libjamesdsp.so
[ -f "$MODPATH/common/files/$QARCH32/libjamesdsp.so" ] && cp_ch $MODPATH/common/files/$QARCH32/libjamesdsp.so $MODPATH/system/vendor/lib/soundfx/libjamesdsp.so
[ -z "$QARCH64" ] || [ ! -f "$MODPATH/common/files/$QARCH64/libjamesdsp.so" ] || cp_ch $MODPATH/common/files/$QARCH64/libjamesdsp.so $MODPATH/system/lib64/soundfx/libjamesdsp.so
[ -z "$QARCH64" ] || [ ! -f "$MODPATH/common/files/$QARCH64/libjamesdsp.so" ] || cp_ch $MODPATH/common/files/$QARCH64/libjamesdsp.so $MODPATH/system/vendor/lib64/soundfx/libjamesdsp.so
# Also place libs in vendor and vendor/vendor trees for ROMs that resolve either mount path.
if ! $KSU; then
  [ -f "$MODPATH/common/files/$QARCH32/libjamesdsp.so" ] && cp_ch $MODPATH/common/files/$QARCH32/libjamesdsp.so $MODPATH/vendor/lib/soundfx/libjamesdsp.so
  [ -z "$QARCH64" ] || [ ! -f "$MODPATH/common/files/$QARCH64/libjamesdsp.so" ] || cp_ch $MODPATH/common/files/$QARCH64/libjamesdsp.so $MODPATH/vendor/lib64/soundfx/libjamesdsp.so
  [ -f "$MODPATH/common/files/$QARCH32/libjamesdsp.so" ] && cp_ch $MODPATH/common/files/$QARCH32/libjamesdsp.so $MODPATH/vendor/vendor/lib/soundfx/libjamesdsp.so
  [ -z "$QARCH64" ] || [ ! -f "$MODPATH/common/files/$QARCH64/libjamesdsp.so" ] || cp_ch $MODPATH/common/files/$QARCH64/libjamesdsp.so $MODPATH/vendor/vendor/lib64/soundfx/libjamesdsp.so
fi
# KSU: audio_effects.xml references libqcomvisualizer.so (not libjamesdsp.so), so place
# the arm64 lib under that name so AudioFlinger finds the correct effect plugin.
if $KSU && [ -n "$QARCH64" ] && [ -f "$MODPATH/common/files/$QARCH64/libjamesdsp.so" ]; then
  cp_ch $MODPATH/common/files/$QARCH64/libjamesdsp.so $MODPATH/system/lib64/soundfx/libqcomvisualizer.so
  cp_ch $MODPATH/common/files/$QARCH64/libjamesdsp.so $MODPATH/system/vendor/lib64/soundfx/libqcomvisualizer.so
fi

# App only works when installed normally to data in oreo+
INSVER=$(pm list packages -3 --show-versioncode | grep james.dsp | sed 's/.*versionCode://')

ui_print " "
ui_print "- UI installation -"
ui_print "   Installing JamesDSP (with pitch shift)..."
mv -f $MODPATH/common/files/JamesDSPManager.apk $MODPATH/JamesDSPManager.apk
touch $MODPATH/.apkorig
uninstall_app "orig" $APPVER

ui_print " "
ui_print "   Patching existing audio_effects files..."
PARTITIONS="/system /vendor $PARTITIONS"
CFGS="$(find $PARTITIONS -type f \( -name "*audio_effects*.conf" -o -name "*audio_effects*.xml" \) 2>/dev/null)"
[ -z "$CFGS" ] && CFGS="/vendor/etc/audio_effects.conf /vendor/etc/audio_effects.xml /odm/etc/audio_effects.conf /odm/etc/audio_effects.xml /product/etc/audio_effects.conf /product/etc/audio_effects.xml /system_ext/etc/audio_effects.conf /system_ext/etc/audio_effects.xml /system/etc/audio_effects.conf /system/etc/audio_effects.xml /system/vendor/etc/audio_effects.conf /system/vendor/etc/audio_effects.xml"
PATCHED_CFGS=0
for OFILE in ${CFGS}; do
  SRC="$(cfg_source_path "$OFILE")"
  [ -z "$SRC" ] && continue
  FILE="$MODPATH$OFILE"
  FILE="$(map_cfg_target "$FILE")"
  cp_ch -n "$SRC" "$FILE"
  patch_audio_cfg_file "$FILE"
  PATCHED_CFGS=$((PATCHED_CFGS + 1))
  # Some ROM/magisk combinations resolve vendor overlays from /vendor while others use /system/vendor.
  if ! $KSU; then
    case $FILE in
      $MODPATH/system/vendor/*)
        ALTFILE="$(echo "$FILE" | sed "s|^$MODPATH/system/vendor|$MODPATH/vendor|")"
        cp_ch -n "$SRC" "$ALTFILE"
        patch_audio_cfg_file "$ALTFILE"
        PATCHED_CFGS=$((PATCHED_CFGS + 1))
        ;;
      $MODPATH/vendor/*)
        ALTFILE="$(echo "$FILE" | sed "s|^$MODPATH/vendor|$MODPATH/system/vendor|")"
        cp_ch -n "$SRC" "$ALTFILE"
        patch_audio_cfg_file "$ALTFILE"
        PATCHED_CFGS=$((PATCHED_CFGS + 1))
        ;;
    esac
  fi
done
ui_print "   Patched $PATCHED_CFGS audio_effects target(s)"

# Extra partition bind-mount support for regular magisk
if $KSU; then
  sed -i "1a NVBASE=$NVBASE" $MODPATH/service.sh
elif $EXTRAPART || { [ ! -d $MODPATH/system/vendor ] && [ ! -d $MODPATH/vendor ] && [ ! -d $MODPATH/system/odm ] && [ ! -d $MODPATH/system/product ] && [ ! -d $MODPATH/system/system_ext ]; }; then
  rm -f $MODPATH/service.sh
else
  sed -i "1a NVBASE=$NVBASE" $MODPATH/service.sh
fi

ui_print "   Copying apk to /sdcard. Install manually if not present on reboot"
cp -rf $MODPATH/common/files/JamesDSP /storage/emulated/0/JamesDSP
cp -f $MODPATH/JamesDSPManager.apk /storage/emulated/0/JamesDSPManager.apk
[ $API -gt 29 ] && { ui_print "   Enabling hidden api policy"; settings put global hidden_api_policy 1 2>/dev/null; }
