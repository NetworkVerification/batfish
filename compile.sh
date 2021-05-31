echo "init-snapshot $1 tr-compile-$1
get compile singlePrefix=\"true\", doNextHop=\"false\", doData=\"$8\", doMultiPath=\"$9\", doNodeFaults=\"$3\", doASPath=\"$6\", doBoundedLinkFaults=\"$4\", symbolicOrder=\"$5\", doOrigin=\"$7\", file=\"$2\"" >> command


allinone -cmd command
rm command
#sh sanitize.sh $2
