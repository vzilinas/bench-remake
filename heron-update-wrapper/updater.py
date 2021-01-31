import subprocess
import sys

topology_name = sys.argv[1]
command_type = sys.argv[2]
parameter = sys.argv[3]
value = sys.argv[4]
command = ''
if command_type == "parallelism":
    command = f'heron update local {topology_name} --component-parallelism={parameter}:{value}'
elif command_type == "configuration":
    command = f'heron restart local {topology_name} --config-parameter={parameter}:{value}'
else:
    raise Exception("Incorrect command_type supplied")

print(command)

