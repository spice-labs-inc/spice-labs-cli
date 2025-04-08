# Spice Grinder
This is a set of tools to scan your systems and upload the results to a Spice Labs server.

## grinder.sh
[grinder.sh](grinder.sh) is the most common way to interact with the toolset.  Feel free to view so that you are comfortable with the script.  At a basic level it is downloading the latest container that holds both [goatrodeo](https://github.com/spice-labs-inc/goatrodeo) and [ginger](https://github.com/spice-labs-inc/ginger).

It will then run the docker image passing in the various command line arguments to mount directories and run goatrodeo, ginger or both.

Here is a list of possible command line options:

```
Syntax: grinder.sh [-b|c|d|j|m|o]

scan:: Launches goatrodeo scanner to generate adgs from container images or applications.

upload:: Packages to a Spice Labs server using security keys supplied

run:: Run both the scan and upload the output

options:
-b     For scan, the source directory or file to scan
-c     What command to run? scan, upload, or run
-d     Override underlying docker image (not common just for testing)
-j     For upload, the JWT token (either file or token) for uploading to Spice Labs server
-m     For upload, the mime type of the upload. Defaults to 'gr' if not passed in.
-o     For scan or upload, directory that output should be directed to or uploaded from
```

If I had some files to scan in a folder named ```playground/test_data```, I wanted the scan output to be stored in ```playground/output```, and I have a JWT or API token ```Downloads\spice_pass.jwt``` I could run a command to scan and upload with:

``` bash
./grinder.sh -c all -b ~/playground/test_data -o ~/playground/output -j ~/Downloads/spice_pass.jwt
```

## I don't want to run your grinder.sh file

Alternatively you can download the container and run it all yourself.

This will run in a docker container.  It combines the functionality of both goatrodeo and ginger by wrapping both.

Here are the overall options for grinder
```
Syntax: grind.sh [-b|c|j|k|m|o|p|s|z]

adg:: Launches goatrodeo generate adgs from container images or applications.

upload:: packages to a Wasabi server using security keys supplied

options:  
-b:     For adg, the source directory or file to scan  
-c:     Command either 'adg' or 'upload'  
-j:     For upload, the JWT token for uploading to Wasabi  
-k:     For upload, the key for encrypting files for uploading to Wasabi  
-m:     For upload, the mime type of the upload  
-o:     For adg, directory that output should be directed to 
-p:     For upload, directory or file that should be uploaded to Wasabi  
-s:     For upload, the server address/endpoint to upload to  
-z:     For upload, the path to a zip file that contains security credentials for Wasabi
```

Normally these will be called from a container so you will have something like:

``` bash
docker run --rm -v ./local/path/to/scan:/tmp/scan -v ./local/path/to/goatrodeo/output:/tmp/output spicelabs/grinder -c adg -b /tmp/scan -0 /tmp/output
```

then you can upload results by:

``` bash
 docker run --rm -v ./local/path/to/goatrodeo/output:/tmp/output -v ./local/path/to/certs/zip:/tmp/certs spicelabs/grinder -c upload -p /tmp/output -z /tmp/certs/certs.zip -m gr 
 ```
  
