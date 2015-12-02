#!/bin/bash

echo "Provisioning interfaces for multi cloud tests..."

# Cleaning previous bridge if exists
sudo ip link set dev brmulti down
sudo brctl delbr brmulti

sudo pipework/pipework brmulti -i multi0 mnsandboxmdts_midolman1_1 10.60.0.1/24
sudo pipework/pipework brmulti -i multi0 mnsandboxmdts_midolman2_1 10.60.0.2/24
sudo pipework/pipework brmulti -i multi0 mnsandboxmdts_midolman3_1 10.60.0.3/24

sudo pipework/pipework brmulti -i multi1 mnsandboxmdts_midolman1_1 10.70.0.1/24
sudo pipework/pipework brmulti -i multi1 mnsandboxmdts_midolman2_1 10.70.0.2/24
sudo pipework/pipework brmulti -i multi1 mnsandboxmdts_midolman3_1 10.70.0.3/24
