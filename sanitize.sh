perl -i -pe 's/.*\n//' $1
perl -i -pe 's/.*?answer\":\"//' $1
perl -i -pe 's/\\n/\n/g' $1
perl -i -pe 's/\"}].*$//s' $1
