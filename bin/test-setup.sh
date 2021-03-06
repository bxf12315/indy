#!/bin/bash
#
# Copyright (C) 2011 Red Hat, Inc. (jdcasey@commonjava.org)
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#         http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#


REPO_BASE="indy/var/lib/indy/data/indy"
ETC_BASE="indy/etc/indy"

DIR=$(dirname $(dirname $(realpath $0)))
if [ "x${TEST_REPOS}" != "x" ]; then
  TEST_REPOS=$(realpath $TEST_REPOS)
fi

if [ "x${TEST_ETC}" != "x" ]; then
  TEST_ETC=$(realpath $TEST_ETC)
fi

pushd $DIR

pushd $DIR/deployments/launchers/savant/target/
rm -rf indy
tar -zxvf indy-launcher-savant-*-launcher.tar.gz

if [ "x${TEST_REPOS}" != "x" ]; then
  rm -rf $REPO_BASE/*
  mkdir -p $REPO_BASE
  cp -rvf $TEST_REPOS/* $REPO_BASE
fi

if [ "x${TEST_ETC}" != "x" ]; then
  cp -rvf $TEST_ETC/* $ETC_BASE
else
  INDY_HOME=$PWD/indy
  cat > $ETC_BASE/logging/logback.xml <<-EOF
<configuration>
  <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
    <!-- encoders are assigned the type
         ch.qos.logback.classic.encoder.PatternLayoutEncoder by default -->
    <encoder>
      <pattern>[%thread] %-5level %logger{36} - %msg%n</pattern>
    </encoder>
  </appender>
  <appender name="FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
    <file>${INDY_HOME}/var/log/indy/indy.log</file>
    <rollingPolicy class="ch.qos.logback.core.rolling.FixedWindowRollingPolicy">
      <fileNamePattern>${INDY_HOME}/var/log/indy/indy.%i.log</fileNamePattern>

      <maxHistory>20</maxHistory>
    </rollingPolicy>

    <triggeringPolicy class="ch.qos.logback.core.rolling.SizeBasedTriggeringPolicy">
        <maxFileSize>100MB</maxFileSize>
    </triggeringPolicy>

    <encoder>
      <pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
    </encoder>
  </appender>
  
  <logger name="org.jboss" level="ERROR"/>
  <logger name="org.commonjava" level="TRACE" />
  <root level="DEBUG">
    <appender-ref ref="STDOUT" />
    <appender-ref ref="FILE" />
  </root>
</configuration>
EOF
fi

popd

$DIR/bin/debug-launcher.rb -e
