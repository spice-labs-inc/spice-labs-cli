**Spice Grinder**

This is the container to execute in your environment to gather logs, adg of applications or containers; encrypt the contents with your key; and securely upload to a Spice Labs server.


This will run in a docker container.  It combines the functionality of both goatrodeo and ginger by wrapping both.

Here are the overall options for grinder
Syntax: grind.sh [-b|c|j|k|m|o|p|s|z]

adg:: Launches goatrodeo generate adgs from container images or applications.

upload:: packages to a Wasabi server using security keys supplied

options:
-b     For adg, the source directory or file to scan
-c     Command either 'adg' or 'upload'
-j     For upload, the JWT token for uploading to Wasabi
-k     For upload, the key for encrypting files for uploading to Wasabi
-m     For upload, the mime type of the upload
-o     For adg, directory that output should be directed to
-p     For upload, directory or file that should be uploaded to Wasabi
-s     For upload, the server address/endpoint to upload to
-z     For upload, the path to a zip file that contains security credentials for Wasabi

Normally these will be called from a container so you will have something like:

docker run --rm -v ./local/path/to/scan:/tmp/scan -v ./local/path/to/goatrodeo/output:/tmp/output spicelabs/grinder -c adg -b /tmp/scan -0 /tmp/output

then you can upload results by:

docker run --rm -v ./local/path/to/goatrodeo/output:/tmp/output -v ./local/path/to/certs/zip:/tmp/certs spicelabs/grinder -c upload -p /tmp/output -z /tmp/certs/certs.zip -m gr


