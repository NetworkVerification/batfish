echo "init-snapshot $1 tr-compile-$1
get compile singlePrefix=\"true\", doNextHop=\"false\", doData=\"false\", doMultiPath=\"false\", doNodeFaults=\"$3\", doASPath=\"false\", doBoundedLinkFaults=\"$4\", file=\"$2\"" >> command


allinone -cmd command
rm command
#sh sanitize.sh $2
