#!/usr/bin/env bash

# install dependencies
export QT_SDK_DIR=/home/vagrant/Qt5.4.0
cd /rstudio/dependencies/linux
./install-dependencies-debian

# resiliency (in case the above aborts early)
./install-dependencies-debian

# run overlay script if present
if [ -f ./install-overlay-debian ]; then
    ./install-overlay-debian
fi 

# configure a basic c/c++ editing experience inside the VM 
cp /rstudio/vagrant/vimrc /home/vagrant/.vimrc
mkdir -p /home/vagrant/.vimbackup
mkdir -p /home/vagrant/.vimswap
mkdir -p /home/vagrant/.vim/bundle/
git clone https://github.com/VundleVim/Vundle.vim.git /home/vagrant/.vim/bundle/Vundle.vim
cd /home/vagrant
vim -T builtin_dumb +PluginInstall +qall
cd /home/vagrant/.vim/bundle/YouCompleteMe
./install.py --clang-completer

# run common user provisioning script
cd /rstudio/vagrant
./provision-common-user.sh

# create build folder and run cmake
mkdir -p /home/vagrant/rstudio-build
cd /home/vagrant/rstudio-build
cmake ~/rstudio/src/cpp -DCMAKE_EXPORT_COMPILE_COMMANDS=1

