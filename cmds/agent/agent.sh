#!/system/bin/sh
export CLASSPATH=/system/framework/agent.jar
exec app_process /system/bin com.android.commands.agent.Agent "$@"
