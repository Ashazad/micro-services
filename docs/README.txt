Please run this particular script for compilation:

Use this command on the runme.sh file to give it proper permissions
chmod +x runme.sh

Important: ISCS is not implemented for A1. Please do not run the command below. It will be implemented for A2.
./runme.sh -i  --> DO NOT run, will not start ISCS

./runme.sh -c  --> compiles all the java source files. 
./runme.sh -u  --> starts the User service
./runme.sh -p  --> starts the Product service
./runme.sh -o  --> starts the Order service
./runme.sh -w workloadfile --> starts the workload parser on the same machine as the order service