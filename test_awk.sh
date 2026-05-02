ALL_PKGS="package:dev.nekohasekai.sfa uid:10388
package:dev.okhsunrog.vpnhide uid:10366
package:com.android.chrome uid:10187"

echo "$ALL_PKGS" | awk -v p="package:dev.nekohasekai.sfa" '$1 == p { sub(/uid:/, "", $2); print $2; exit }' | tr ',' '\n'
