echo "init-snapshot $1 tr-compile-$1
get compile singlePrefix=\"false\", doNextHop=\"false\"" >> command

allinone -cmd command > $2
rm command
sh sanitize.sh $2

