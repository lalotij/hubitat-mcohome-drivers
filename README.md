# Hubitat drivers for MCOHome / SFire Z-Wave touch switches

Custom Hubitat Elevation drivers for the MCOHome MH-S510 series of Z-Wave Plus touch panel switches. In Mexico and Latin America these are sold under the **SFire** brand as MHS511, MHS511SL, MHS512 and MHS512SL.

| Driver | Models | Status |
|---|---|---|
| `MCOHome MH-S511 Switch` | MH-S511, MHS511, MHS511SL (1 gang) | Stable, tested on MHS511SL |
| `MCOHome MH-S512 Dual Switch` | MH-S512, MHS512, MHS512SL (2 gang) | Stable, tested on MHS512SL |

## Problems these drivers solve

With the built-in generic drivers:

- **The state flips back to `off` 1–2 seconds after turning the light on from Hubitat**, even though the light stays on. After a command, the firmware sends a correct `SwitchBinaryReport` and then a bogus one with the opposite value.
- **Physical presses on the panel are not reflected in Hubitat.** The firmware does not report local presses through the lifeline (group 1). It only sends them as Basic frames to the nodes in association group 2 (and group 3 for the second gang).
- **On the 2-gang model, `on` turns both gangs on at once.** The device is multi channel, and the generic driver only talks to the root endpoint.

## What the drivers do

- After any change, they ignore `SwitchBinaryReport`s that contradict it for a few seconds. The window is configurable and defaults to 5 s.
- They do not poll the device right after a command, because that poll is what returns the wrong value.
- **Configure** adds the hub to association group 2 (and group 3 on the MH-S512). Physical presses then arrive as Basic frames, which are always trusted.
- MH-S512: a child `Generic Component Switch` is created for each gang, so the two can be controlled independently. The parent device switches both gangs and shows `on` if either one is on.

## Installation

1. In Hubitat, go to **Drivers Code → New Driver → Import**. Paste the raw URL of the driver file and click **Save**.
   - `https://raw.githubusercontent.com/lalotij/hubitat-mcohome-drivers/main/drivers/MCOHome_MH-S511_Switch.groovy`
   - `https://raw.githubusercontent.com/lalotij/hubitat-mcohome-drivers/main/drivers/MCOHome_MH-S512_Dual_Switch.groovy`
2. Open the device, go to **Device Info → Type**, choose the driver and click **Save**.
3. On the **Commands** tab, click **Configure**. The logs should show `Group 2 ... -> hub OK`.
4. MH-S512 only: use the two child devices ("Button 1" and "Button 2") in your dashboards and rules.

You can also install the drivers with [Hubitat Package Manager](https://community.hubitat.com/t/release-hubitat-package-manager-hpm-hubitatcommunity/94471).

## Preferences

| Setting | Default | Notes |
|---|---|---|
| Ignore contradicting reports for N seconds | 5 | Raise it if a bogus `off` still gets through. Lower it if you toggle the switch very quickly from Hubitat. |
| Poll state every N seconds (MH-S511 only) | 0 (off) | A fallback for units that never report physical presses. The minimum is 10 s. |
| Enable debug logging | on | Turns off automatically after 30 minutes. |

## Known limitations

- On the MH-S512, group 2/3 Basic frames are not endpoint-encapsulated, so the driver cannot tell which gang was pressed. When one arrives, the driver waits for the ignore window and then polls both gangs. Physical presses therefore show up a few seconds late.
- **Refresh** may return a wrong value if it is issued within a second or two of a change. This is a firmware bug.

## License

MIT License. See [LICENSE](LICENSE).
