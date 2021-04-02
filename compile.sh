echo "init-snapshot $1 tr-compile-$1
get compile singlePrefix=\"true\", doNextHop=\"true\", doData=\"false\", doMultiPath=\"false\", doNodeFaults=\"true\", doASPath=\"true\", doBoundedLinkFaults=\"2\", file=\"$2\"" >> command


allinone -cmd command
rm command
ocp-indent -i $2*.nv
#sh sanitize.sh $2
