#!/usr/bin/env bash
#
#

set -e # exit on failure

# Pull the listed imaged
while read -r line; do
  echo "Pulling image ${line}"
  docker pull "${line}" 
done < "$1"


target_date_name="$(date +%Y_%m_%d_%H_%M_%S)"

target_dir="adg_${target_date_name}"

docker_blob="docker_blob_${target_date_name}.tar"

blob_dir="blob_dir_${target_date_name}"

mkdir "${blob_dir}"

echo "Saving images to ${blob_dir}/${docker_blob}"
docker save "$(cat "$1")" -o "${blob_dir}/${docker_blob}"

mkdir "$target_dir"

echo "Running Goat Rodeo and putting the ADG in ${target_dir}"
docker run -ti --rm -u "$(id -u "${USER}"):$(id -g "${USER}")" -v "$(pwd)":/data \
       ghcr.io/spice-labs-inc/goatrodeo:0.6.3 \
       -b "/data/${blob_dir}" \
       -o "/data/${target_dir}/docker_image_adg"

echo "Cleaning Up"
rm -r "${blob_dir}"

echo "The Artifact Dependency Graph is in ${target_dir}"
