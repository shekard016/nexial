#!/bin/bash

NEXIAL_HOME=$(cd `dirname $0`/..; pwd -P)
. ${NEXIAL_HOME}/bin/.commons.sh
title "nexial docs minifier"
checkJava
resolveEnv

# run now
${JAVA} -classpath "${NEXIAL_CLASSES}:${NEXIAL_LIB}/nexial*.jar:${NEXIAL_LIB}/*:${USER_HOME_NEXIAL_LIB}/*" ${JAVA_OPT} \
	org.nexial.core.tools.docs.MinifyGenerator $*

exit $?
