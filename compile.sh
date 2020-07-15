echo "init-snapshot $1 tr-compile-$1
get compile singlePrefix=\"false\", doNextHop=\"true\", file=\"$2\"" >> command


echo "init-snapshot $1 tr-compile-$1
get compile singlePrefix=\"false\", doNextHop=\"true\", file=\"$2\""


allinone -cmd command
rm command
#sh sanitize.sh $2
