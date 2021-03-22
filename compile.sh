echo "init-snapshot $1 tr-compile-$1
get compile singlePrefix=\"true\", doNextHop=\"false\", doData=\"false\", doMultiPath=\"true\", doNodeFaults=\"false\", doASPath=\"false\", doBoundedLinkFaults=\"2\", file=\"$2\"" >> command


allinone -cmd command
rm command
ocp-indent -i $2*.nv
#sh sanitize.sh $2
