#! /bin/bash

# this is going to be the SH file to do the work
############################################################
# Help                                                     #
############################################################
Help()
{
   # Display Help
   echo "Many options to support goatrodeo and ginger processes which are the scan/adg generation and upload respectively."
   echo
   echo "Syntax: grind [adg|upload] [-b|j|k|o|p|s|z]"
   echo ""
   echo "adg:: Launches goatrodeo generate adgs from container images or applications."
   echo ""
   echo "upload:: packages to a Wasabi server using security keys supplied"
   echo ""
   echo "options:"
   echo "b     For adg, the source directory or file to scan"
   echo "j     For upload, the JWT token for uploading to Wasabi"
   echo "k     For upload, the key for encrypting files for uploading to Wasabi"
   echo "o     For adg, directory that output should be directed to"
   echo "p     For upload, directory or file that should be uploaded to Wasabi"
   echo "s     For upload, the server address/endpoint to upload to"
   echo "z     For upload, the path to a zip file that contains security credentials for Wasabi"
   echo
}

############################################################
############################################################
# Main program                                             #
############################################################
############################################################
############################################################
# Process the input options. Add options as needed.        #
############################################################
# Get the options
while getopts ":bhjkopsz:" option; do
   case $option in
      h) # display Help
         Help
         exit;;
     \?) # Invalid option
         echo "Error: Invalid option"
         Help
         exit;;
   esac
done


echo "do some work"