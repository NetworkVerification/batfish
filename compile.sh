echo "init-snapshot $1 tr-compile-$1
get compile singlePrefix=\"true\", doNextHop=\"true\", file=\"$2\"" >> command


echo "init-snapshot $1 tr-compile-$1
get compile singlePrefix=\"true\", doNextHop=\"true\", file=\"$2\""


allinone -cmd command
rm command
ocp-indent -i $2*.nv
#sh sanitize.sh $2
