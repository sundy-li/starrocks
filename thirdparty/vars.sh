#!/bin/bash
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

############################################################
# You may have to set variables bellow,
# which are used for compiling thirdparties and starrocks itself.
############################################################

# --job param for *make*
PARALLEL=$[$(nproc)/4+1]

###################################################
# DO NOT change variables bellow unless you known 
# what you are doing.
###################################################

# thirdparties will be downloaded and unpacked here
export TP_SOURCE_DIR=$TP_DIR/src

# thirdparties will be installed to here
export TP_INSTALL_DIR=$TP_DIR/installed

# patches for all thirdparties
export TP_PATCH_DIR=$TP_DIR/patches

# header files of all thirdparties will be intalled to here
export TP_INCLUDE_DIR=$TP_INSTALL_DIR/include

# libraries of all thirdparties will be intalled to here
export TP_LIB_DIR=$TP_INSTALL_DIR/lib

# all java libraries will be unpacked to here
export TP_JAR_DIR=$TP_INSTALL_DIR/lib/jar

#####################################################
# Download url, filename and unpaced filename
# of all thirdparties
#####################################################

# libevent
# the last release version of libevent is 2.1.8, which was released on 26 Jan 2017, that is too old.
# so we use the master version of libevent, which is downloaded on 22 Jun 2018, with commit 24236aed01798303745470e6c498bf606e88724a
LIBEVENT_DOWNLOAD="https://github.com/libevent/libevent/archive/24236ae.zip"
LIBEVENT_NAME=libevent-24236aed01798303745470e6c498bf606e88724a.zip
LIBEVENT_SOURCE=libevent-24236aed01798303745470e6c498bf606e88724a
LIBEVENT_MD5SUM="c6c4e7614f03754b8c67a17f68177649"

# openssl
OPENSSL_DOWNLOAD="https://www.openssl.org/source/old/1.0.2/openssl-1.0.2k.tar.gz"
OPENSSL_NAME=openssl-1.0.2k.tar.gz
OPENSSL_SOURCE=openssl-1.0.2k
OPENSSL_MD5SUM="f965fc0bf01bf882b31314b61391ae65"

# thrift
THRIFT_DOWNLOAD="http://archive.apache.org/dist/thrift/0.13.0/thrift-0.13.0.tar.gz"
THRIFT_NAME=thrift-0.13.0.tar.gz
THRIFT_SOURCE=thrift-0.13.0
THRIFT_MD5SUM="38a27d391a2b03214b444cb13d5664f1"

# protobuf
PROTOBUF_DOWNLOAD="https://github.com/google/protobuf/archive/v3.5.1.tar.gz"
PROTOBUF_NAME=protobuf-3.5.1.tar.gz
PROTOBUF_SOURCE=protobuf-3.5.1
PROTOBUF_MD5SUM="710f1a75983092c9b45ecef207236104"

# gflags
GFLAGS_DOWNLOAD="https://github.com/gflags/gflags/archive/v2.2.0.tar.gz"
GFLAGS_NAME=gflags-2.2.0.tar.gz
GFLAGS_SOURCE=gflags-2.2.0
GFLAGS_MD5SUM="b99048d9ab82d8c56e876fb1456c285e"

# glog
GLOG_DOWNLOAD="https://github.com/google/glog/archive/v0.3.3.tar.gz"
GLOG_NAME=glog-0.3.3.tar.gz
GLOG_SOURCE=glog-0.3.3
GLOG_MD5SUM="c1f86af27bd9c73186730aa957607ed0"

# gtest
GTEST_DOWNLOAD="https://github.com/google/googletest/archive/release-1.8.0.tar.gz"
GTEST_NAME=googletest-release-1.8.0.tar.gz
GTEST_SOURCE=googletest-release-1.8.0
GTEST_MD5SUM="16877098823401d1bf2ed7891d7dce36"

# snappy
SNAPPY_DOWNLOAD="https://github.com/google/snappy/archive/1.1.7.tar.gz"
SNAPPY_NAME=snappy-1.1.7.tar.gz
SNAPPY_SOURCE=snappy-1.1.7
SNAPPY_MD5SUM="ee9086291c9ae8deb4dac5e0b85bf54a"

# gperftools
GPERFTOOLS_DOWNLOAD="https://github.com/gperftools/gperftools/archive/gperftools-2.7.tar.gz"
GPERFTOOLS_NAME=gperftools-2.7.tar.gz
GPERFTOOLS_SOURCE=gperftools-gperftools-2.7
GPERFTOOLS_MD5SUM="797e7b7f6663288e2b90ab664861c61a"

# zlib
ZLIB_DOWNLOAD="https://zlib.net/zlib-1.2.11.tar.gz"
ZLIB_NAME=zlib-1.2.11.tar.gz
ZLIB_SOURCE=zlib-1.2.11 
ZLIB_MD5SUM="1c9f62f0778697a09d36121ead88e08e"

# lz4
LZ4_DOWNLOAD="https://github.com/lz4/lz4/archive/v1.7.5.tar.gz"
LZ4_NAME=lz4-1.7.5.tar.gz
LZ4_SOURCE=lz4-1.7.5
LZ4_MD5SUM="c9610c5ce97eb431dddddf0073d919b9"

# bzip
BZIP_DOWNLOAD="https://fossies.org/linux/misc/bzip2-1.0.8.tar.gz"
BZIP_DOWNLOAD="ftp://sourceware.org/pub/bzip2/bzip2-1.0.8.tar.gz"
BZIP_NAME=bzip2-1.0.8.tar.gz
BZIP_SOURCE=bzip2-1.0.8
BZIP_MD5SUM="67e051268d0c475ea773822f7500d0e5"

# lzo2
LZO2_DOWNLOAD="http://www.oberhumer.com/opensource/lzo/download/lzo-2.10.tar.gz"
LZO2_NAME=lzo-2.10.tar.gz
LZO2_SOURCE=lzo-2.10
LZO2_MD5SUM="39d3f3f9c55c87b1e5d6888e1420f4b5"

# rapidjson
RAPIDJSON_DOWNLOAD="https://github.com/miloyip/rapidjson/archive/v1.1.0.tar.gz"
RAPIDJSON_NAME=rapidjson-1.1.0.tar.gz
RAPIDJSON_SOURCE=rapidjson-1.1.0
RAPIDJSON_MD5SUM="badd12c511e081fec6c89c43a7027bce"

# curl
CURL_DOWNLOAD="https://curl.haxx.se/download/curl-7.54.1.tar.gz"
CURL_NAME=curl-7.54.1.tar.gz
CURL_SOURCE=curl-7.54.1
CURL_MD5SUM="21a6e5658fd55103a90b11de7b2a8a8c"

# RE2
RE2_DOWNLOAD="https://github.com/google/re2/archive/2017-05-01.tar.gz"
RE2_NAME=re2-2017-05-01.tar.gz
RE2_SOURCE=re2-2017-05-01
RE2_MD5SUM="4aa65a0b22edacb7ddcd7e4aec038dcf"

# boost
BOOST_DOWNLOAD="http://sourceforge.net/projects/boost/files/boost/1.75.0/boost_1_75_0.tar.gz"
BOOST_NAME=boost_1_75_0.tar.gz
BOOST_SOURCE=boost_1_75_0
BOOST_MD5SUM="38813f6feb40387dfe90160debd71251"

# mysql
MYSQL_DOWNLOAD="https://github.com/mysql/mysql-server/archive/mysql-5.7.18.tar.gz"
MYSQL_NAME=mysql-5.7.18.tar.gz
MYSQL_SOURCE=mysql-server-mysql-5.7.18
MYSQL_MD5SUM="58598b10dce180e4d1fbdd7cf5fa68d6"

# boost for mysql
BOOST_FOR_MYSQL_DOWNLOAD="http://sourceforge.net/projects/boost/files/boost/1.59.0/boost_1_59_0.tar.gz"
BOOST_FOR_MYSQL_NAME=boost_1_59_0.tar.gz
BOOST_FOR_MYSQL_SOURCE=boost_1_59_0
BOOST_FOR_MYSQL_MD5SUM="51528a0e3b33d9e10aaa311d9eb451e3"

# leveldb
LEVELDB_DOWNLOAD="https://github.com/google/leveldb/archive/v1.20.tar.gz"
LEVELDB_NAME=leveldb-1.20.tar.gz
LEVELDB_SOURCE=leveldb-1.20
LEVELDB_MD5SUM="298b5bddf12c675d6345784261302252"

# brpc
BRPC_DOWNLOAD="https://github.com/apache/incubator-brpc/archive/0.9.5.tar.gz"
BRPC_NAME=incubator-brpc-0.9.5.tar.gz
BRPC_SOURCE=incubator-brpc-0.9.5
BRPC_MD5SUM="c9f46e4c97a9cd5f836ba2c6c56978dd"

# rocksdb
ROCKSDB_DOWNLOAD="https://github.com/facebook/rocksdb/archive/refs/tags/v6.22.1.zip"
ROCKSDB_NAME=rocksdb-6.22.1.zip
ROCKSDB_SOURCE=rocksdb-6.22.1
ROCKSDB_MD5SUM="02727e52cdb94fa6a9dbbd68d157e619"

# librdkafka
LIBRDKAFKA_DOWNLOAD="https://github.com/edenhill/librdkafka/archive/v0.11.6-RC5.tar.gz"
LIBRDKAFKA_NAME=librdkafka-0.11.6-RC5.tar.gz
LIBRDKAFKA_SOURCE=librdkafka-0.11.6-RC5
LIBRDKAFKA_MD5SUM="2e4ecef2df277e55a0144eb6d185e18a"

# zstd
ZSTD_DOWNLOAD="https://github.com/facebook/zstd/archive/v1.3.7.tar.gz"
ZSTD_NAME=zstd-1.3.7.tar.gz
ZSTD_SOURCE=zstd-1.3.7
ZSTD_MD5SUM="9b89923a360ac85a3b8076fdf495318d"

# double-conversion
DOUBLE_CONVERSION_DOWNLOAD="https://github.com/google/double-conversion/archive/v3.1.1.tar.gz"
DOUBLE_CONVERSION_NAME=double-conversion-3.1.1.tar.gz
DOUBLE_CONVERSION_SOURCE=double-conversion-3.1.1
DOUBLE_CONVERSION_MD5SUM="befd431c3de3f3ed7926ba2845ee609d"

# brotli
BROTLI_DOWNLOAD="https://github.com/google/brotli/archive/v1.0.7.tar.gz"
BROTLI_NAME="brotli-1.0.7.tar.gz"
BROTLI_SOURCE="brotli-1.0.7"
BROTLI_MD5SUM="7b6edd4f2128f22794d0ca28c53898a5"

# flatbuffers
FLATBUFFERS_DOWNLOAD="https://github.com/google/flatbuffers/archive/v1.10.0.tar.gz"
FLATBUFFERS_NAME=flatbuffers-v1.10.0.tar.gz
FLATBUFFERS_SOURCE=flatbuffers-1.10.0
FLATBUFFERS_MD5SUM="f7d19a3f021d93422b0bc287d7148cd2"

# arrow
ARROW_DOWNLOAD="https://github.com/apache/arrow/archive/apache-arrow-0.15.1.tar.gz"
ARROW_NAME="arrow-apache-arrow-0.15.1.tar.gz"
ARROW_SOURCE="arrow-apache-arrow-0.15.1"
ARROW_MD5SUM="7d822863bbbe409794c6cdec3dcb8e6c"

# S2
S2_DOWNLOAD="https://github.com/google/s2geometry/archive/v0.9.0.tar.gz"
S2_NAME=s2geometry-0.9.0.tar.gz
S2_SOURCE=s2geometry-0.9.0
S2_MD5SUM="293552c7646193b8b4a01556808fe155"

# BITSHUFFLE
BITSHUFFLE_DOWNLOAD="https://github.com/kiyo-masui/bitshuffle/archive/0.3.5.tar.gz"
BITSHUFFLE_NAME=bitshuffle-0.3.5.tar.gz
BITSHUFFLE_SOURCE=bitshuffle-0.3.5
BITSHUFFLE_MD5SUM="2648ec7ccd0b896595c6636d926fc867"

# CROARINGBITMAP
CROARINGBITMAP_DOWNLOAD="https://github.com/RoaringBitmap/CRoaring/archive/v0.2.60.tar.gz"
CROARINGBITMAP_NAME=CRoaring-0.2.60.tar.gz
CROARINGBITMAP_SOURCE=CRoaring-0.2.60
CROARINGBITMAP_MD5SUM="29602918e6890ffdeed84cb171857046"

# jemalloc
JEMALLOC_DOWNLOAD="https://github.com/jemalloc/jemalloc/releases/download/5.2.1/jemalloc-5.2.1.tar.bz2"
JEMALLOC_NAME="jemalloc-5.2.1.tar.bz2"
JEMALLOC_SOURCE="jemalloc-5.2.1"
JEMALLOC_MD5SUM="3d41fbf006e6ebffd489bdb304d009ae"

# CCTZ
CCTZ_DOWNLOAD="https://github.com/google/cctz/archive/v2.3.tar.gz"
CCTZ_NAME="cctz-2.3.tar.gz"
CCTZ_SOURCE="cctz-2.3"
CCTZ_MD5SUM="209348e50b24dbbdec6d961059c2fc92"

# GOOGLE BENCHMARK
BENCHMARK_DOWNLOAD="https://github.com/google/benchmark/archive/v1.5.1.zip"
BENCHMARK_NAME="benchmark-1.5.1.zip"
BENCHMARK_SOURCE="benchmark-1.5.1"
BENCHMARK_MD5SUM="5cc3c1cd63f02c42340540f1ab0de330"

# FMT
FMT_DOWNLOAD="https://github.com/fmtlib/fmt/releases/download/7.0.3/fmt-7.0.3.zip"
FMT_NAME="fmt-7.0.3.zip"
FMT_SOURCE="fmt-7.0.3"
FMT_MD5SUM="60c8803eb36a6ff81a4afde33c0f621a"

# RYU
RYU_DOWNLOAD="https://github.com/ulfjack/ryu/archive/aa31ca9361d21b1a00ee054aac49c87d07e74abc.zip"
RYU_NAME="ryu-aa31ca9361d21b1a00ee054aac49c87d07e74abc.zip"
RYU_SOURCE="ryu-aa31ca9361d21b1a00ee054aac49c87d07e74abc"
RYU_MD5SUM="cb82b6da904d919470fe3f5a01ca30ff"

# breakpad
BREAK_PAD_DOWNLOAD="https://github.com/google/breakpad/archive/d6a6f52.zip"
BREAK_PAD_NAME="breakpad-d6a6f52606529111b9f0ade9a0e0d9040fa97c1f.zip"
BREAK_PAD_SOURCE="breakpad-d6a6f52606529111b9f0ade9a0e0d9040fa97c1f"
BREAK_PAD_MD5SUM="53e8e9ee2d5e4f842a0cb4d651e74af6"

# HADOOP
HADOOP_DOWNLOAD="http://dorisdb-thirdparty.oss-cn-zhangjiakou.aliyuncs.com/hadoop-3.3.0.tar.gz"
HADOOP_NAME="hadoop-3.3.0.tar.gz"
HADOOP_SOURCE="hadoop-3.3.0"
HADOOP_MD5SUM="c4ba5155ec44abcb63d183d2dbe38df9"

# OPEN JDK
JDK_DOWNLOAD="http://dorisdb-thirdparty.oss-cn-zhangjiakou.aliyuncs.com/java-se-8u41-ri.tar.gz"
JDK_NAME="java-se-8u41-ri.tar.gz"
JDK_SOURCE="java-se-8u41-ri"
JDK_MD5SUM="7295b5a3fb90e7aaf80df23d5eac222d"

# RAGEL
# ragel-6.9+ is used by hypercan, so we build it first
RAGEL_DOWNLOAD="https://www.colm.net/files/ragel/ragel-6.10.tar.gz"
RAGEL_NAME="ragel-6.10.tar.gz"
RAGEL_SOURCE="ragel-6.10"
RAGEL_MD5SUM="748cae8b50cffe9efcaa5acebc6abf0d"

#z HYPERSCAN
HYPERSCAN_DOWNLOAD="https://github.com/intel/hyperscan/archive/v5.4.0.tar.gz"
HYPERSCAN_NAME="hyperscan-5.4.0.tar.gz"
HYPERSCAN_SOURCE="hyperscan-5.4.0"
HYPERSCAN_MD5SUM="65e08385038c24470a248f6ff2fa379b"

# all thirdparties which need to be downloaded is set in array TP_ARCHIVES
TP_ARCHIVES="LIBEVENT OPENSSL THRIFT PROTOBUF GFLAGS GLOG GTEST RAPIDJSON SNAPPY GPERFTOOLS ZLIB LZ4 BZIP LZO2 CURL RE2 BOOST MYSQL BOOST_FOR_MYSQL LEVELDB BRPC ROCKSDB LIBRDKAFKA FLATBUFFERS ARROW BROTLI DOUBLE_CONVERSION ZSTD S2 BITSHUFFLE CROARINGBITMAP JEMALLOC CCTZ BENCHMARK FMT RYU BREAK_PAD HADOOP JDK RAGEL HYPERSCAN"
