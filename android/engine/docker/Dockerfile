# Builder for the on-device engine pack. linux/arm64 only (glibc, curl and git are compiled natively for aarch64).
# Build tools come from the live Debian mirror; every library that ships in the pack is pinned in userland/debs.lock.
FROM debian:trixie@sha256:9c6e381421d0f48d1b8133e573f33c185ee16cbf505eda9c4a8a4791b25ea8a6
ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update -qq \
	&& apt-get install -y -qq --no-install-recommends \
		build-essential gawk bison python3 jq wget curl ca-certificates xz-utils zstd gettext texinfo file git \
		patchelf binutils pkg-config autoconf automake libtool \
	&& rm -rf /var/lib/apt/lists/*
