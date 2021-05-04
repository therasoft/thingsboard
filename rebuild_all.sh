#!/bin/sh
cd thingsboard

sudo mvn clean install -DskipTests 

cd ..

#stop service
sudo service thingsboard stop

#remove package
sudo dpkg -r thingsboard

#install package
sudo dpkg -i thingsboard/application/target/thingsboard.deb

#restart service
sudo systemctl daemon-reload | sudo service thingsboard start

echo "Done!"
