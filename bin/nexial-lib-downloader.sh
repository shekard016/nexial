#!/usr/bin/env bash

NEXIAL_HOME=$(cd `dirname $0`/..; pwd -P)
. ${NEXIAL_HOME}/bin/.commons.sh
checkJava

# download nexial-lib-x.x.zip to userhome/.nexial/lib
LIB_VERSION_FILE=nexial-lib-version.txt
LIB_DOWNLOADER='${JAVA} ${MAX_MEM} \
    -classpath "${PROJECT_CLASSPATH}:${NEXIAL_CLASSES}:${NEXIAL_LIB}/nexial*.jar:${NEXIAL_LIB}/*:${USER_NEXIAL_JAR}/*:${USER_NEXIAL_LIB}/*" \
    ${JAVA_OPT} \
    org.nexial.core.tools.NexialLibDownloader ${NEXIAL_HOME}'


if [ -e "${USER_NEXIAL_LIB}/${LIB_VERSION_FILE}" ]
then
  if [ -n "$(cmp ${USER_NEXIAL_LIB}/${LIB_VERSION_FILE} ${NEXIAL_HOME}/lib/${LIB_VERSION_FILE})" ]
  then
    eval "$LIB_DOWNLOADER"
  fi
else
  eval "$LIB_DOWNLOADER"
fi
ret=$?

exit ${ret}
