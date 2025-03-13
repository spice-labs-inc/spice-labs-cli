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
   echo "-b     For adg, the source directory or file to scan"
   echo "-c     Command either 'adg' or 'upload'"
   echo "-j     For upload, the JWT token for uploading to Wasabi"
   echo "-k     For upload, the key for encrypting files for uploading to Wasabi"
   echo "-o     For adg, directory that output should be directed to"
   echo "-p     For upload, directory or file that should be uploaded to Wasabi"
   echo "-s     For upload, the server address/endpoint to upload to"
   echo "-z     For upload, the path to a zip file that contains security credentials for Wasabi"
   echo;
   exit 1;
}

############################################################
############################################################
# Main program                                             #
############################################################
############################################################
############################################################
# Process the input options. Add options as needed.        #
############################################################

#Setup some variables
#adg build directory
#BUILDDIR = " "
#command to run
#COMMAND = " "
#jwt token for upload
#JWT = " "
#public key for upload
#PUBLICKEY = " "
#output directory for adg
#OUTPUTDIR = " "
#upload dir
#UPLOADDIR = " "
#wasabi server
#SERVERNAME = " "
#zip file path with creditials
#ZIPFILEPATH = " "

# Get the options
while getopts ":b:c:hj:k:o:p:s:z:" option; do
   case $option in
      c) 
         command=${OPTARG} >&2

         #it needs to be "adg" or "upload" or call the help
         #if [ "$command" != "adg" ] && [ "$command" != "upload" ] then
         if [[ "$command" != "adg" ]] && [[ "$command" != "upload" ]]; then
            echo "Command needs to be either adg or upload"
            Help
         fi
         ;;
      h) # display Help
         Help
         exit;;
     \?) # Invalid option
         echo "Error: Invalid option"
         Help
         exit 1;;
   esac
done


echo "do the "$command""