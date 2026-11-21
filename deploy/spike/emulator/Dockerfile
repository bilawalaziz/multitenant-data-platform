# The emulator has no published image; this is the vendor-documented Dockerfile.
# The vendor URL only serves "latest", so the RPM is pinned by checksum (v1.0.556).
FROM public.ecr.aws/amazonlinux/amazonlinux:2023-minimal
ADD --checksum=sha256:5a304befce3a3638e119353e1092837b6f1e1df372f2ed66d1f911948a912c96 \
    https://downloads.aws.com/aws-emulator/packages/aws-emulator/LTS/latest/aws-emulator-x86_64.rpm /opt/aws-emulator.rpm
RUN rpm -i /opt/aws-emulator.rpm && rm /opt/aws-emulator.rpm \
    && mkdir -p /home/emulator && chown 1000 /home/emulator
USER 1000
ENV HOME=/home/emulator EMULATOR_BIND_ADDRESS=0.0.0.0
ENTRYPOINT ["aws-emulator"]
