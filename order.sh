sh runme.sh -c
docker stop $(docker ps -aq) && docker rm $(docker ps -aq)
docker-compose up --build -d
