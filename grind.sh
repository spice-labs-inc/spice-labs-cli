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
   echo "Syntax: grind.sh [-b|c|j|k|m|o|p|s|z]"
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
   echo "-m     For upload, the mime type of the upload"
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

#plan to use these variables

#adg build directory
#BUILDDIR = " "
#command to run
#COMMAND = " "
#jwt token for upload
#JWT = " "
#mime type for upload
#mime_type = ""
# key for upload
#PUBLICKEY = " "
#output directory for adg
#OUTPUTDIR = " "
#payload for ginger can be file or path
#payload=""
#upload dir
#UPLOADDIR = " "
#wasabi server
#SERVERNAME = " "
#zip file path with creditials
#ZIPFILEPATH = " "

#directory holding binaries in container
#MUST NOT HAVE TRAILING SLASH
binarydir="/usr/bin"

# Set the directory containing the jar files
lib_dir="/opt/docker/lib"


# Get the options
while getopts ":b:c:hj:k:m:o:p:s:u:z:" option; do
   case $option in
      b)
         builddir=${OPTARG} >&2
         ;;
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
      j)
         jwt=${OPTARG} >&2
         ;;
      k)
         publickey=${OPTARG} >&2
         ;;
      m)
         mime_type=${OPTARG} >&2
         ;;
      o)
         outputdir=${OPTARG} >&2
         ;;
      p)
         payload=${OPTARG} >&2
         ;;
      s)
         servername=${OPTARG} >&2
         ;;
      u)
         uploaddir=${OPTARG} >&2
         ;;
      z)
         zipfile=${OPTARG} >&2
         ;;
     \?) # Invalid option
         echo "Error: Invalid option"
         Help
         exit 1;;
   esac
done

#echo "Done with variables"

if [[ "$command" == "adg" ]]; then
   #echo "Run goatrodeo command"
   /opt/docker/bin/goatrodeo -b $builddir -o $outputdir
elif [[ "$command" == "upload" ]]; then
   #echo "fire ginger command"
   #check to see if zip file passed in and exists
      if [[ -n "$zipfile" ]] && [[ -f $zipfile ]]; then
            #call ginger with zipfile
            echo "$binarydir/ginger -p $payload -z $zipfile -m $mime_type" 
            $binarydir/ginger -p $payload -z $zipfile -m $mime_type
      else
            #call ginger with payload, mime, jwt
            echo "$binarydir/ginger -p $payload -j $jwt  -m $mime_type"
            $binarydir/ginger -p $payload -j $jwt -m $mime_type
      fi

else
   #should never get here but just in case....
   echo "invalid command argument"
   Help
   exit 1;
fi
