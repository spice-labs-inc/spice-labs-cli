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

#plan to use these variables

#adg build directory
#BUILDDIR = " "
#command to run
#COMMAND = " "
#jwt token for upload
#JWT = " "
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
binarydir="."

# Get the options
while getopts ":b:c:hj:k:o:p:s:u:z:" option; do
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

echo "Done with variables"

if [[ "$command" == "adg" ]]; then
   echo "firing goatrodeo command"
   echo "java -jar $binarydir/goatrodeo.jar -b $builddir -o $outputdir"
   java -jar $binarydir/goatrodeo.jar -b $builddir -o $outputdir
   
elif [[ "$command" == "upload" ]]; then
   echo "fire ginger command"
   #check to see if zip file passed in and exists
      if [[ -n "$zipfile" ]] && [[ -f $zipfile ]]; then
            #call ginger with zipfile
            echo "$binarydir/ginger -p $payload -z $zipfile" 
            $binarydir/ginger -p $payload -z $zipfile
      else
            #call ginger with server publickey and jwt
            echo "$binarydir/ginger -p $payload -s $servername -j $jwt -k $publickey"
            $binarydir/ginger -p $payload -s $server -j $jwt -k $publickey
      fi

else
   #should never get here but just in case....
   echo "invalid command argument"
   exit 1;
fi
