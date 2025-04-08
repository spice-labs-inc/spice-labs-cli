#! /bin/bash

# this is going to be the SH file to be the wrapper for the docker file
############################################################
# Help                                                     #
############################################################
Help()
{
   # Display Help
   echo ""
   echo "Tool to scan and then upload results to a Spice Labs server"
   echo
   echo "Syntax: grinder.sh [-b|c|d|j|m|o]"
   echo ""
   echo "scan:: Launches goatrodeo scanner to generate adgs from container images or applications."
   echo ""
   echo "upload:: Packages to a Spice Labs server using security keys supplied"
   echo ""
   echo "all:: Run the scan and upload the output "
   echo ""
   echo "options:"
   echo "-b     For scan, the source directory or file to scan"
   echo "-c     What command to run? scan, upload, or all"
   echo "-d     Override underlying docker image (not common just for testing)"
   echo "-j     For upload, the JWT token (either file or token) for uploading to Spice Labs server"
   echo "-m     For upload, the mime type of the upload. Defaults to 'gr' if not passed in."
   echo "-o     For scan or upload, directory that output should be directed to or uploaded from"
   echo;
   exit 1;
}

Scan()
{
    docker run --rm -v "$builddir":/tmp/input -v "$outputdir":/tmp/output "$dockerimage" -c adg -b /tmp/input -o /tmp/output 
}

Upload()
{
    #did we get a JWT file that we have to mount or get passed in the string from a secret?
    if [ -f "$jwt" ]; then
        docker run --rm  -v "$outputdir":/tmp/output -v "$jwt_directory":/tmp/certs "$dockerimage" -c upload -p /tmp/output -j "$jwt_path" -m "$mime_type" 
    else
        docker run --rm  -v "$outputdir":/tmp/output "$dockerimage" -c upload -p /tmp/output -j "$jwt_path" -m "$mime_type" 
    fi
}

############################################################
############################################################
# Main program                                             #
############################################################
############################################################
############################################################
# Process the input options.                               #
############################################################

# Get the options
#default dockerimage
dockerimage="ghcr.io/spice-labs-inc/grinder:latest"

#default mime_type
mime_type="gr"

# Get the options
while getopts ":b:c:d:hj:m:o:" option; do
   case $option in
      b)
         builddir=${OPTARG} >&2
         ;;
      c) 
         command=${OPTARG} >&2

         #it needs to be "adg" or "upload" or call the help
         #if [ "$command" != "adg" ] && [ "$command" != "upload" ] then
         if [[ "$command" != "scan" ]] && [[ "$command" != "upload" ]] && [[ "$command" != "all" ]]; then
            echo "Command needs to be either scan or upload or all"
            Help
         fi
         ;;
      d)
         dockerimage=${OPTARG} >&2
         ;;
      h) # display Help
         Help
         ;;
      j)
         jwt=${OPTARG} >&2
         ;;
      m)
         mime_type=${OPTARG} >&2
         ;;
      o)
         outputdir=${OPTARG} >&2
         ;;
     \?) # Invalid option
         echo "Error: Invalid option"
         Help
         ;;
   esac
done

if [ -z "$command" ]; then
    echo "Command is required"
    Help
fi
#for key, zipfile, or jwt need to break out paths and files
#make sure file exists

if [ -f $jwt ]; then
    jwt_directory=$(dirname "$jwt")
    jwt_file=$(basename "$jwt")
    jwt_path="/tmp/certs/$jwt_file"
else
    #it's not a file so let's assume it is passed in command line 
    jwt_path=$jwt
fi

#get latest grinder image
docker pull ghcr.io/spice-labs-inc/grinder:latest

case $command in
    scan)
        #docker run --rm -v $builddir:/tmp/input -v $outputdir:/tmp/output ghcr.io/spice-labs-inc/grinder:latest -c adg -b /tmp/input -o /tmp/output
        Scan
        ;;
    upload)
        #docker run --rm  -v $outputdir:/tmp/output ghcr.io/spice-labs-inc/grinder:latest -c upload -p /tmp/output -k $publickey -s $servername -j $jwt -z $zipfile -m $mime_type
        #docker run --rm  -v $outputdir:/tmp/output f3eb230f3c0c -c upload -p /tmp/output -j $jwt -z $zipfile -m $mime_type -k $publickey -s $servername
        Upload
        ;;
    all)
        Scan
        Upload
        ;;
esac

