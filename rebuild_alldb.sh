#!/bin/sh
cd thingsboard

sudo mvn clean install -DskipTests 

cd ..

#stop service
sudo service thingsboard stop

#drop db from CLI
sudo -u postgres dropdb thingsboard

#remove package
sudo dpkg -r thingsboard

#recreate database from CLI
sudo -u postgres createdb thingsboard

#install package
sudo dpkg -i thingsboard.deb

echo "siguientes pasos manuales:"

echo "revisar pass db >>>  sudo nano /etc/thingsboard/conf/thingsboard.conf"

echo "cambiar puerto a 5000  >>>  sudo nano /etc/thingsboard/conf/thingsboard.yml"

echo "instalar datos demo >>> sudo /usr/share/thingsboard/bin/install/install.sh --loadDemo"

echo "reinicia servicio >>> sudo systemctl daemon-reload | sudo service thingsboard start"

