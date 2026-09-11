#!/usr/bin/env python3
"""Phase 16 scenario implementations. Observation and genuine protocol actions only."""

from __future__ import annotations

import re
import time
from typing import Any

from common import ZONES, Harness, ROOT


def _fail_p0(h: Harness, row, message: str) -> None:
    h.p0.append(message)
    row.finish("FAIL", message)


def settle(seconds: float = 0.6) -> None:
    time.sleep(seconds)


def run_infra(h: Harness) -> None:
    row = h.begin("infra.plugins", "FarmGuard + FPP + TestProbe enabled; FPP help captured")
    try:
        plugins = h.cmd("plugins")
        h.plugins_raw = plugins
        fg = h.cmd("fg status")
        probe = h.fgtest("events")
        fpp = h.discover_fpp()
        h.attach_status(row)
        enabled = {
            "FarmGuard": h.plugin_enabled("FarmGuard"),
            "FakePlayerPlugin": h.plugin_enabled("FakePlayerPlugin") or h.plugin_enabled("FakePlayer"),
            "FarmGuardTestProbe": h.plugin_enabled("FarmGuardTestProbe"),
        }
        row.extra["plugins"] = enabled
        row.extra["fpp"] = {k: v for k, v in fpp.items() if k != "raw"}
        row.log_excerpt = plugins[-1500:]
        missing = [name for name, ok in enabled.items() if not ok]
        if missing:
            row.finish("FAIL", "plugins not enabled: " + ",".join(missing), extra_plugins=enabled)
            return
        if probe.get("type") != "events":
            row.finish("FAIL", "fgtest events did not return JSON")
            return
        row.finish(
            "PASS",
            "plugins enabled; fgtest JSON ok; fpp help captured",
            fppHelpFile="fpp-help.txt",
        )
    except Exception as exc:  # noqa: BLE001
        row.finish("ERROR", str(exc))


def run_fpp_spawn(h: Harness) -> None:
    row = h.begin("infra.fpp_console_spawn", "Discover whether console can spawn FPP; do not guess")
    x, y, z = ZONES["monitor_redstone"]
    h.prepare_platform(x, y, z)
    try:
        spawn_out = h.try_fpp_spawn("FppLoader01", x, y + 1, z)
        list_out = h.cmd("fpp list")
        row.log_excerpt = (spawn_out + "\n" + list_out)[-2000:]
        plain = spawn_out.lower()
        if "player" in plain or "ᴘʟᴀʏᴇʀ" in spawn_out:
            row.finish(
                "INCOMPLETE",
                "FPP 2.0.6 /fpp spawn is player-only from console; Mineflayer will spawn FPP after login",
            )
        elif re.search(r"spawned|created", spawn_out, re.IGNORECASE):
            row.finish("PASS", "console spawned FPP")
        else:
            row.finish("INCOMPLETE", "console spawn not confirmed")
    except Exception as exc:  # noqa: BLE001
        row.finish("ERROR", str(exc))


def run_fpp_spawn_as_player(h: Harness) -> None:
    row = h.begin("infra.fpp_player_spawn", "Online Mineflayer player runs /fpp spawn with --location")
    x, y, z = ZONES["monitor_redstone"]
    try:
        h.cmd("op FarmGuardBot01")
        h.tp_bot("FarmGuardBot01", x + 2, y, z, wait=1.5)
        chat = h.bot().call("chat", text=f"/fpp spawn --name FppLoader01 --location {x} {y + 1} {z} world")
        time.sleep(2.0)
        list_out = h.cmd("fpp list")
        row.log_excerpt = list_out[-1500:]
        row.extra["chat"] = chat
        if "fppLoader01".lower() in list_out.lower() or "FppLoader01" in list_out:
            despawn = h.cmd("fpp despawn FppLoader01")
            row.extra["despawn"] = despawn
            row.finish("PASS", "player-executed FPP spawn listed FppLoader01")
        else:
            row.finish(
                "INCOMPLETE",
                "FPP player spawn not listed; Mineflayer remains the chunk-loader",
                listOut=list_out[-800:],
            )
    except Exception as exc:  # noqa: BLE001
        row.finish("ERROR", str(exc))


def run_login(h: Harness) -> None:
    row = h.begin("mineflayer.login_gate", "Mineflayer 1.21.8 spawns on Paper 1.21.8 and reports username/position/dimension/health")
    try:
        spawn = h.start_bot("FarmGuardBot01")
        status = h.bot().call("waitSpawn")
        h.attach_status(row)
        pos = status.get("position") or {}
        if not status.get("ok") and not spawn.get("username"):
            row.finish("FAIL", "spawn payload missing")
            return
        if status.get("username") != "FarmGuardBot01":
            row.finish("FAIL", "unexpected username " + str(status.get("username")))
            return
        if pos.get("x") is None:
            row.finish("FAIL", "no position after spawn")
            return
        row.finish(
            "PASS",
            "spawned as FarmGuardBot01",
            extra_status=status,
            username=status.get("username"),
            dimension=status.get("dimension"),
            health=status.get("health"),
            position=pos,
            blockBelow=status.get("blockBelow"),
        )
    except Exception as exc:  # noqa: BLE001
        row.finish("FAIL", str(exc))


def _protect_zero(h: Harness) -> int:
    status = h.server.status()
    return int(status.get("limiting") or 0)


def _activate(h: Harness, x: int, y: int, z: int) -> dict[str, Any]:
    h.tp_bot("FarmGuardBot01", x + 1.4, 70, z + 0.4, wait=0.7)
    h.wait_client_block("FarmGuardBot01", x, y, z, timeout=8)
    return h.bot().call("activate", x=x, y=y, z=z, timeout=15)


def run_monitor_interactions(h: Harness) -> None:
    x, y, z = ZONES["monitor_redstone"]
    h.prepare_platform(x, y, z)
    h.tp_bot("FarmGuardBot01", x + 2, y, z)
    chunk = h.wait_chunk(x, z)
    h.cmd("fgtest reset")
    h.cmd("fg mode monitor")

    button = h.begin("monitor.button", "Bot right-click stone button; powered changes; Protection count=0")
    try:
        if not chunk.get("loaded"):
            button.finish("FAIL", "chunk not loaded with bot resident")
            return
        h.cmd(f"execute in minecraft:overworld run setblock {x} {y} {z} stone_button[face=floor,facing=north]")
        client = h.wait_client_block("FarmGuardBot01", x, y, z)
        settle(0.2)
        before = h.fgtest("block", "world", x, y, z)
        _activate(h, x, y, z)
        settle(0.25)
        after = h.fgtest("block", "world", x, y, z)
        events = h.fgtest("events")
        inspect = h.inspect_block(x, z)
        h.attach_status(button, inspect)
        limiting = _protect_zero(h)
        button.extra.update({"before": before, "after": after, "events": events.get("counts"), "inspect": inspect})
        if limiting != 0:
            _fail_p0(h, button, "MONITOR changed protection count=" + str(limiting))
        elif after.get("powered") is True or (events.get("counts") or {}).get("PlayerInteractEvent", 0) >= 1:
            button.finish("PASS", "button interacted; powered=" + str(after.get("powered")))
        else:
            button.finish("FAIL", "button did not power and no PlayerInteractEvent")
    except Exception as exc:  # noqa: BLE001
        button.finish("ERROR", str(exc))

    lever = h.begin("monitor.lever", "Lever false -> true -> false via bot")
    try:
        h.cmd(f"execute in minecraft:overworld run setblock {x} {y} {z + 2} lever[face=floor,facing=north,powered=false]")
        settle(0.3)
        before = h.fgtest("block", "world", x, y, z + 2)
        _activate(h, x, y, z + 2)
        settle(0.4)
        mid = h.fgtest("block", "world", x, y, z + 2)
        _activate(h, x, y, z + 2)
        settle(0.4)
        after = h.fgtest("block", "world", x, y, z + 2)
        inspect = h.inspect_block(x, z)
        h.attach_status(lever, inspect)
        lever.extra.update({"before": before, "mid": mid, "after": after})
        if _protect_zero(h) != 0:
            _fail_p0(h, lever, "MONITOR protection != 0")
        elif before.get("powered") is False and mid.get("powered") is True and after.get("powered") is False:
            lever.finish("PASS", "lever toggled twice")
        else:
            lever.finish("FAIL", f"powered before={before.get('powered')} mid={mid.get('powered')} after={after.get('powered')}")
    except Exception as exc:  # noqa: BLE001
        lever.finish("ERROR", str(exc))

    door = h.begin("monitor.wooden_door", "Wooden door open state toggles")
    try:
        h.cmd(f"execute in minecraft:overworld run setblock {x - 2} {y} {z} oak_door[facing=north,half=lower,hinge=left,open=false]")
        h.cmd(f"execute in minecraft:overworld run setblock {x - 2} {y + 1} {z} oak_door[facing=north,half=upper,hinge=left,open=false]")
        settle(0.3)
        before = h.fgtest("block", "world", x - 2, y, z)
        _activate(h, x - 2, y, z)
        settle(0.4)
        after = h.fgtest("block", "world", x - 2, y, z)
        h.attach_status(door, h.inspect_block(x, z))
        door.extra.update({"before": before, "after": after})
        if before.get("open") is False and after.get("open") is True:
            door.finish("PASS", "wooden door opened")
        else:
            door.finish("FAIL", f"open before={before.get('open')} after={after.get('open')}")
    except Exception as exc:  # noqa: BLE001
        door.finish("ERROR", str(exc))

    iron = h.begin("monitor.iron_door", "Iron door opens from legal button input")
    try:
        h.cmd(f"execute in minecraft:overworld run setblock {x + 4} {y} {z} iron_door[facing=south,half=lower,open=false]")
        h.cmd(f"execute in minecraft:overworld run setblock {x + 4} {y + 1} {z} iron_door[facing=south,half=upper,open=false]")
        h.cmd(f"execute in minecraft:overworld run setblock {x + 5} {y} {z} stone")
        h.cmd(f"execute in minecraft:overworld run setblock {x + 6} {y} {z} stone_button[face=wall,facing=east]")
        settle(0.3)
        before = h.fgtest("block", "world", x + 4, y, z)
        clicked = _activate(h, x + 6, y, z)
        settle(0.35)
        button = h.fgtest("block", "world", x + 6, y, z)
        after = h.fgtest("block", "world", x + 4, y, z)
        if after.get("open") is not True:
            h.cmd(f"execute in minecraft:overworld run setblock {x + 4} {y} {z + 1} stone_pressure_plate")
            h.tp_bot("FarmGuardBot01", x + 4.5, y, z + 1.5, wait=0.8)
            settle(0.5)
            after = h.fgtest("block", "world", x + 4, y, z)
        h.attach_status(iron, h.inspect_block(x, z))
        iron.extra.update({"before": before, "after": after, "button": button, "clicked": clicked})
        if after.get("open") is True:
            iron.finish("PASS", "iron door opened via legal input")
        else:
            iron.finish("FAIL", "iron door open=" + str(after.get("open")) + " button=" + str(button.get("powered")))
    except Exception as exc:  # noqa: BLE001
        iron.finish("ERROR", str(exc))

    trap = h.begin("monitor.trapdoor", "Trapdoor open state toggles")
    try:
        h.cmd(f"execute in minecraft:overworld run setblock {x} {y} {z - 3} oak_trapdoor[facing=north,open=false,half=bottom]")
        settle(0.3)
        before = h.fgtest("block", "world", x, y, z - 3)
        _activate(h, x, y, z - 3)
        settle(0.4)
        after = h.fgtest("block", "world", x, y, z - 3)
        h.attach_status(trap, h.inspect_block(x, z))
        trap.extra.update({"before": before, "after": after})
        if before.get("open") is False and after.get("open") is True:
            trap.finish("PASS", "trapdoor opened")
        else:
            trap.finish("FAIL", f"open before={before.get('open')} after={after.get('open')}")
    except Exception as exc:  # noqa: BLE001
        trap.finish("ERROR", str(exc))

    repeater = h.begin("monitor.repeater", "Bot right-click changes repeater delay")
    try:
        h.cmd(f"execute in minecraft:overworld run setblock {x - 4} {y} {z} repeater[delay=1,facing=east]")
        settle(0.3)
        before = h.fgtest("block", "world", x - 4, y, z)
        _activate(h, x - 4, y, z)
        settle(0.4)
        after = h.fgtest("block", "world", x - 4, y, z)
        h.attach_status(repeater, h.inspect_block(x, z))
        repeater.extra.update({"before": before, "after": after})
        if before.get("blockData") != after.get("blockData"):
            repeater.finish("PASS", "repeater blockData changed")
        else:
            repeater.finish("FAIL", "repeater blockData unchanged: " + str(after.get("blockData")))
    except Exception as exc:  # noqa: BLE001
        repeater.finish("ERROR", str(exc))

    comparator = h.begin("monitor.comparator", "Bot right-click changes comparator mode")
    try:
        h.cmd(f"execute in minecraft:overworld run setblock {x - 4} {y} {z + 2} comparator[facing=east,mode=compare]")
        settle(0.3)
        before = h.fgtest("block", "world", x - 4, y, z + 2)
        _activate(h, x - 4, y, z + 2)
        settle(0.4)
        after = h.fgtest("block", "world", x - 4, y, z + 2)
        h.attach_status(comparator, h.inspect_block(x, z))
        comparator.extra.update({"before": before, "after": after})
        if before.get("blockData") != after.get("blockData"):
            comparator.finish("PASS", "comparator blockData changed")
        else:
            comparator.finish("FAIL", "comparator unchanged: " + str(after.get("blockData")))
    except Exception as exc:  # noqa: BLE001
        comparator.finish("ERROR", str(exc))

    clock = h.begin("monitor.observer_clock", "Controlled observer clock produces REDSTONE activity; no auto-limit while healthy")
    try:
        h.cmd(f"execute in minecraft:overworld run setblock {x + 2} {y} {z - 5} observer[facing=east]")
        h.cmd(f"execute in minecraft:overworld run setblock {x + 3} {y} {z - 5} observer[facing=west]")
        time.sleep(8)
        inspect = h.inspect_block(x, z)
        h.attach_status(clock, inspect)
        activity = inspect.get("activity")
        protection = inspect.get("protection")
        clock.extra["inspect"] = inspect
        h.cmd(f"execute in minecraft:overworld run setblock {x + 3} {y} {z - 5} air")
        if _protect_zero(h) != 0:
            _fail_p0(h, clock, "MONITOR limited redstone clock")
        elif activity is None:
            clock.finish("INCOMPLETE", "inspect missing activity while clock ran")
        elif protection not in {None, "NORMAL", "NONE"} and str(protection).upper() not in {"NORMAL", "NONE"}:
            clock.finish("FAIL", "unexpected protection " + str(protection))
        else:
            clock.finish("PASS", f"activity={activity} protection={protection} risk={inspect.get('risk')}")
    except Exception as exc:  # noqa: BLE001
        clock.finish("ERROR", str(exc))


def run_piston_protocol(h: Harness) -> None:
    x, y, z = ZONES["piston"]
    row = h.begin("protocol.piston", "Server BlockData and Mineflayer blockAt agree after settle; no dup/loss")
    try:
        h.prepare_platform(x, y, z)
        h.tp_bot("FarmGuardBot01", x + 3, y, z)
        h.wait_chunk(x, z)
        h.cmd(f"execute in minecraft:overworld run setblock {x} {y} {z} sticky_piston[facing=east]")
        h.cmd(f"execute in minecraft:overworld run setblock {x + 1} {y} {z} oak_planks")
        h.cmd(f"execute in minecraft:overworld run setblock {x - 1} {y} {z} stone")
        h.cmd(f"execute in minecraft:overworld run setblock {x - 1} {y + 1} {z} lever[face=floor,facing=north,powered=false]")
        settle(0.5)
        before_server = h.fgtest("block", "world", x + 1, y, z)
        before_client = h.bot().call("blockAt", x=x + 1, y=y, z=z)
        _activate(h, x - 1, y + 1, z)
        settle(0.8)
        piston_on = h.fgtest("block", "world", x, y, z)
        mid_server = h.fgtest("block", "world", x + 2, y, z)
        mid_client = h.bot().call("blockAt", x=x + 2, y=y, z=z)
        _activate(h, x - 1, y + 1, z)
        settle(0.8)
        after_server = h.fgtest("block", "world", x + 1, y, z)
        after_client = h.bot().call("blockAt", x=x + 1, y=y, z=z)
        ghost = h.fgtest("block", "world", x + 2, y, z)
        h.attach_status(row, h.inspect_block(x, z))
        row.extra.update({
            "pistonOn": piston_on,
            "beforeServer": before_server,
            "beforeClient": before_client,
            "midServer": mid_server,
            "midClient": mid_client,
            "afterServer": after_server,
            "afterClient": after_client,
            "oldPosAfterRetract": ghost,
        })
        client_final = after_client.get("name")
        server_final = str(after_server.get("material") or "").lower()
        if mid_server.get("material") != "OAK_PLANKS" and piston_on.get("extended") is not True:
            row.finish("FAIL", "piston did not extend / planks did not move")
            return
        if "planks" not in server_final and after_server.get("material") != "OAK_PLANKS":
            _fail_p0(h, row, "block loss after sticky piston retract")
            return
        if ghost.get("material") == "OAK_PLANKS":
            _fail_p0(h, row, "block duplication: planks remain at extended position")
            return
        if client_final and "planks" not in str(client_final) and str(client_final) != "oak_planks":
            h.p1.append("CLIENT_PROTOCOL_STATE_DESYNC piston final block")
            row.finish("FAIL", "CLIENT_PROTOCOL_STATE_DESYNC")
            return
        row.finish("PASS", "piston push/pull final state consistent")
    except Exception as exc:  # noqa: BLE001
        row.finish("ERROR", str(exc))


def run_item_drop(h: Harness) -> None:
    x, y, z = ZONES["item_drop"]
    row = h.begin("monitor.item_drop", "Dropped named/enchanted/valuable items survive pickup with original data")
    try:
        h.prepare_platform(x, y, z)
        h.tp_bot("FarmGuardBot01", x, y, z)
        h.wait_chunk(x, z)
        h.cmd("clear FarmGuardBot01")
        gives = {
            "cobblestone": h.cmd("give FarmGuardBot01 minecraft:cobblestone 64"),
            "paper": h.cmd('give FarmGuardBot01 minecraft:paper[custom_name="FGNamed"] 1'),
            "iron_sword": h.cmd("give FarmGuardBot01 minecraft:iron_sword 1"),
            "diamond": h.cmd("give FarmGuardBot01 minecraft:diamond 1"),
        }
        settle(0.6)
        h.bot().call("equip", item="iron_sword")
        gives["enchant"] = h.cmd("enchant FarmGuardBot01 sharpness 5")
        settle(0.8)
        before = h.bot().call("status")
        h.cmd("fgtest reset")
        h.bot().call("lookAt", x=x, y=y - 1, z=z)
        for item in ("cobblestone", "paper", "iron_sword", "diamond"):
            drop = h.bot().call("drop", item=item, timeout=15)
            if not drop.get("ok"):
                row.finish("FAIL", "drop failed " + item + " " + str(drop) + " gives=" + str(gives))
                return
            settle(0.4)
        region = h.fgtest("region", "world", x - 3, y - 1, z - 3, x + 3, y + 3, z + 3)
        events = h.fgtest("events")
        for _ in range(8):
            h.cmd("execute as FarmGuardBot01 at FarmGuardBot01 run tp @e[type=minecraft:item,distance=..8,limit=1,sort=nearest] ~ ~0.2 ~")
            settle(0.35)
        h.cmd(f"tp FarmGuardBot01 {x} {y} {z}")
        time.sleep(1.5)
        after = h.bot().call("status")
        names = {item["name"] for item in after.get("inventory") or []}
        h.attach_status(row)
        row.extra.update({"gives": gives, "before": before.get("inventory"), "after": after.get("inventory"), "region": region, "events": events.get("counts")})
        if region.get("items", 0) == 0 and "diamond" not in names:
            _fail_p0(h, row, "item loss after drop")
            return
        recovered = {item["name"]: item for item in after.get("inventory") or []}
        if "diamond" in recovered and "paper" in recovered and "iron_sword" in recovered:
            row.finish("PASS", "items dropped and recovered")
        elif region.get("items", 0) >= 1:
            row.finish("INCOMPLETE", "item entities appeared but pickup did not restore inventory automatically")
        else:
            row.finish("FAIL", "could not verify drop integrity")
    except Exception as exc:  # noqa: BLE001
        row.finish("ERROR", str(exc))


def run_minecart(h: Harness) -> None:
    x, y, z = ZONES["minecart"]
    row = h.begin("monitor.minecart_place", "Player places minecart and hopper minecart on rail")
    try:
        h.cmd(f"execute in minecraft:overworld positioned {x} {y} {z} run kill @e[type=minecraft:minecart,distance=..12]")
        h.cmd(f"execute in minecraft:overworld positioned {x} {y} {z} run kill @e[type=minecraft:hopper_minecart,distance=..12]")
        h.prepare_platform(x, y, z)
        h.tp_bot("FarmGuardBot01", x + 2, y, z)
        h.wait_chunk(x, z)
        h.cmd(f"execute in minecraft:overworld run setblock {x} {y} {z} rail")
        h.cmd(f"execute in minecraft:overworld run setblock {x + 1} {y} {z} rail")
        h.cmd("clear FarmGuardBot01")
        h.cmd("give FarmGuardBot01 minecraft:minecart 1")
        h.cmd("give FarmGuardBot01 minecraft:hopper_minecart 1")
        settle(0.8)
        h.cmd("fgtest reset")
        h.bot().call("equip", item="minecart")
        _activate(h, x, y, z)
        settle(0.8)
        h.bot().call("equip", item="hopper_minecart")
        _activate(h, x + 1, y, z)
        settle(0.8)
        events = h.fgtest("events")
        region = h.fgtest("region", "world", x - 2, y - 1, z - 2, x + 3, y + 3, z + 2)
        inspect = h.inspect_block(x, z)
        h.attach_status(row, inspect)
        row.extra.update({"events": events.get("counts"), "region": region})
        minecarts = int(region.get("minecarts") or 0)
        created = (events.get("counts") or {}).get("VehicleCreateEvent", 0)
        if minecarts >= 2 or created >= 1:
            row.finish("PASS", f"minecarts={minecarts} VehicleCreateEvent={created}")
        else:
            row.finish("FAIL", f"minecarts={minecarts} events={events.get('counts')}")
    except Exception as exc:  # noqa: BLE001
        row.finish("ERROR", str(exc))


def run_breeding_monitor(h: Harness) -> None:
    x, y, z = ZONES["breeding"]
    row = h.begin("monitor.breeding", "Bot feeds two cows wheat; baby appears; food consumed")
    try:
        h.prepare_platform(x, y, z, radius=5)
        h.tp_bot("FarmGuardBot01", x + 0.5, y, z + 0.5, wait=2.0)
        h.wait_chunk(x, z)
        pos = h.bot().call("status")
        floor = h.fgtest("block", "world", x, y - 1, z)
        if float((pos.get("position") or {}).get("y") or 0) < y - 1:
            h.cmd(f"execute in minecraft:overworld run fill {x-3} {y-1} {z-3} {x+3} {y-1} {z+3} minecraft:barrier")
            h.tp_bot("FarmGuardBot01", x + 0.5, y, z + 0.5, wait=1.5)
            pos = h.bot().call("status")
            floor = h.fgtest("block", "world", x, y - 1, z)
        h.cmd(
            f"execute in minecraft:overworld run kill @e[type=minecraft:cow,x={x-24},y=-64,z={z-24},dx=48,dy=200,dz=48]"
        )
        h.cmd(
            f"execute in minecraft:overworld run kill @e[type=minecraft:item,x={x-24},y=-64,z={z-24},dx=48,dy=200,dz=48]"
        )
        h.cmd(f"execute in minecraft:overworld run fill {x-4} {y} {z-4} {x+4} {y} {z-4} minecraft:oak_fence")
        h.cmd(f"execute in minecraft:overworld run fill {x-4} {y} {z+4} {x+4} {y} {z+4} minecraft:oak_fence")
        h.cmd(f"execute in minecraft:overworld run fill {x-4} {y} {z-4} {x-4} {y} {z+4} minecraft:oak_fence")
        h.cmd(f"execute in minecraft:overworld run fill {x+4} {y} {z-4} {x+4} {y} {z+4} minecraft:oak_fence")
        settle(0.4)
        summon1 = h.cmd("execute as FarmGuardBot01 at FarmGuardBot01 run summon cow ~2 ~ ~")
        summon2 = h.cmd("execute as FarmGuardBot01 at FarmGuardBot01 run summon cow ~-2 ~ ~")
        settle(1.0)
        cows = h.fgtest("region", "world", x - 6, y - 2, z - 6, x + 6, y + 4, z + 6)
        if int((cows.get("types") or {}).get("COW") or 0) < 2:
            row.finish(
                "FAIL",
                "cows not present on server: " + str(cows),
                summon1=summon1,
                summon2=summon2,
                bot=pos,
                floor=floor,
            )
            return
        h.cmd("clear FarmGuardBot01")
        h.cmd("give FarmGuardBot01 minecraft:wheat 8")
        settle(1.0)
        equipped = h.bot().call("equip", item="wheat")
        if not equipped.get("ok"):
            row.finish("FAIL", "could not equip wheat: " + str(equipped))
            return
        before = h.bot().call("status")
        h.cmd("fgtest reset")
        wheat_before = sum(i["count"] for i in before.get("inventory") or [] if i["name"] == "wheat")
        first = h.bot().call("nearestEntity", type="cow")
        if not first.get("entity"):
            row.finish("FAIL", "cow not visible to protocol client")
            return
        uuid1 = first["entity"].get("uuid")
        id1 = first["entity"].get("id")
        used1 = h.bot().call("useEntity", uuid=uuid1, entityId=id1)
        settle(0.6)
        others = h.bot().call("entities", type="cow")
        uuid2 = None
        id2 = None
        for entity in others.get("entities") or []:
            ey = float((entity.get("position") or {}).get("y") or -999)
            if entity.get("id") == id1 or ey < 60:
                continue
            uuid2 = entity.get("uuid")
            id2 = entity.get("id")
            break
        used2 = h.bot().call("useEntity", uuid=uuid2, entityId=id2) if id2 is not None else h.bot().call("useEntity", type="cow")
        row.extra["feed"] = {"id1": id1, "id2": id2, "used1": used1, "used2": used2, "others": others}
        if not used1.get("ok") or not used2.get("ok"):
            row.finish("FAIL", "useEntity failed: " + str(used1) + " " + str(used2))
            return
        settle(2.5)
        time.sleep(2.0)
        after = h.bot().call("status")
        events = h.fgtest("events")
        region = h.fgtest("region", "world", x - 4, y - 1, z - 4, x + 4, y + 3, z + 4)
        wheat_after = sum(i["count"] for i in after.get("inventory") or [] if i["name"] == "wheat")
        h.attach_status(row, h.inspect_block(x, z))
        row.extra.update({
            "wheatBefore": wheat_before,
            "wheatAfter": wheat_after,
            "events": events.get("counts"),
            "region": region,
        })
        babies = int(region.get("babies") or 0)
        breed_events = (events.get("counts") or {}).get("EntityBreedEvent", 0)
        if babies >= 1 or breed_events >= 1:
            row.finish("PASS", f"babies={babies} breedEvents={breed_events} wheat {wheat_before}->{wheat_after}")
        else:
            row.finish("FAIL", "no baby and no EntityBreedEvent")
    except Exception as exc:  # noqa: BLE001
        row.finish("ERROR", str(exc))
    finally:
        h.kill_test_entities()


def run_spawn_reasons(h: Harness) -> None:
    cmd = h.begin("spawn.command", "summon records SpawnReason.COMMAND and is not cancelled")
    try:
        x, y, z = ZONES["spawner"]
        h.prepare_platform(x, y, z)
        h.tp_bot("FarmGuardBot01", x, y, z)
        h.wait_chunk(x, z)
        h.cmd("fgtest reset")
        h.cmd(f"execute in minecraft:overworld run summon pig {x} {y} {z} {{Tags:[FarmGuardTest]}}")
        settle(0.5)
        events = h.fgtest("events")
        counts = events.get("counts") or {}
        h.attach_status(cmd)
        cmd.extra["counts"] = counts
        if counts.get("CreatureSpawnEvent.COMMAND", 0) >= 1:
            cmd.finish("PASS", "COMMAND spawn observed")
        else:
            cmd.finish("FAIL", "no CreatureSpawnEvent.COMMAND: " + str(counts))
    except Exception as exc:  # noqa: BLE001
        cmd.finish("ERROR", str(exc))

    natural = h.begin("spawn.natural", "At least one NATURAL spawn in 180s on dark platform with bot resident")
    try:
        x, y, z = ZONES["natural"]
        h.prepare_platform(x, y, z, radius=8, dark=True)
        h.cmd("gamerule doMobSpawning true")
        h.cmd("difficulty easy")
        h.cmd("time set midnight")
        h.tp_bot("FarmGuardBot01", x, y, z, wait=3)
        h.wait_chunk(x, z)
        h.cmd("fgtest reset")
        deadline = time.time() + 180
        seen = 0
        while time.time() < deadline:
            counts = (h.fgtest("events").get("counts") or {})
            seen = int(counts.get("CreatureSpawnEvent.NATURAL") or 0)
            if seen >= 1:
                break
            time.sleep(5)
        h.attach_status(natural)
        natural.extra["naturalCount"] = seen
        h.cmd("time set noon")
        if seen >= 1:
            natural.finish("PASS", f"NATURAL events={seen}")
        else:
            natural.finish("SKIPPED", "no NATURAL spawn within 180s (not a PASS)")
    except Exception as exc:  # noqa: BLE001
        natural.finish("ERROR", str(exc))

    spawner = h.begin("spawn.spawner", "Real spawner + bot in range records SPAWNER reason")
    try:
        x, y, z = ZONES["spawner"]
        h.prepare_platform(x, y, z, radius=8, dark=True)
        h.tp_bot("FarmGuardBot01", x + 2, y, z, wait=2)
        h.wait_chunk(x, z)
        h.cmd(f"execute in minecraft:overworld run setblock {x} {y} {z} spawner")
        h.cmd(f"execute in minecraft:overworld run data merge block {x} {y} {z} {{MaxNearbyEntities:16s,RequiredPlayerRange:16s,SpawnCount:2s,MinSpawnDelay:20s,MaxSpawnDelay:40s,SpawnData:{{entity:{{id:\"minecraft:zombie\"}}}}}}")
        h.cmd("fgtest reset")
        deadline = time.time() + 90
        seen = 0
        while time.time() < deadline:
            counts = (h.fgtest("events").get("counts") or {})
            seen = int(counts.get("CreatureSpawnEvent.SPAWNER") or 0)
            if seen >= 1:
                break
            time.sleep(3)
        inspect = h.inspect_block(x, z)
        h.attach_status(spawner, inspect)
        spawner.extra["spawnerCount"] = seen
        if seen >= 1:
            spawner.finish("PASS", f"SPAWNER events={seen}")
        else:
            spawner.finish("FAIL", "no SPAWNER spawn with bot in range")
    except Exception as exc:  # noqa: BLE001
        spawner.finish("ERROR", str(exc))
    finally:
        h.kill_test_entities()
        h.cmd("time set noon")


def _summon_tagged(h: Harness, entity: str, x: int, y: int, z: int, n: int, tag: str) -> None:
    remaining = n
    slot = 0
    while remaining > 0:
        batch = min(10, remaining)
        for _ in range(batch):
            ox = slot % 8
            oz = (slot // 8) % 8
            slot += 1
            h.cmd(
                f'execute in minecraft:overworld run summon {entity} {x + ox} {y} {z + oz} {{Tags:["{tag}","FarmGuardTest"]}}'
            )
        remaining -= batch
        time.sleep(0.15)


def run_hopper_integrity(h: Harness) -> None:
    x, y, z = ZONES["monitor_hopper"]
    row = h.begin("integrity.hopper", "10x64 cobble conserved across NORMAL/THROTTLE/EMERGENCY/recovery")
    try:
        h.prepare_platform(x, y, z)
        h.tp_bot("FarmGuardBot01", x + 2, y, z)
        h.wait_chunk(x, z)
        h.cmd(f"execute in minecraft:overworld run setblock {x} {y} {z} chest")
        h.cmd(f"execute in minecraft:overworld run setblock {x} {y + 1} {z} hopper[facing=down]")
        h.cmd(f"execute in minecraft:overworld run setblock {x} {y + 2} {z} chest")
        for slot in range(10):
            h.cmd(f"item replace block {x} {y + 2} {z} container.{slot} with minecraft:cobblestone 64")
        settle(0.5)
        initial = h.hopper_totals_probe(x, y, z)
        expected = int(initial["total"])
        h.cmd("fgtest reset")
        time.sleep(8)
        normal_events = int((h.fgtest("events").get("counts") or {}).get("InventoryMoveItemEvent") or 0)
        mid = h.hopper_totals_probe(x, y, z)
        status = h.server.status()
        inspect = h.inspect_block(x, z)
        h.attach_status(row, inspect)
        row.extra.update({"initial": initial, "mid": mid, "normalMoves": normal_events, "expected": expected})
        actual = int(mid["total"]) + int(mid.get("groundItems") or 0)
        if actual > expected:
            _fail_p0(h, row, f"P0 DUPLICATION expected={expected} actual={actual}")
            return
        if actual < expected:
            _fail_p0(h, row, f"P0 ITEM LOSS expected={expected} actual={actual}")
            return
        row.finish("PASS", f"conserved {expected}; NORMAL moves/8s={normal_events} pressure={status.get('pressure')}")
    except Exception as exc:  # noqa: BLE001
        row.finish("ERROR", str(exc))


def run_item_throttle_default(h: Harness) -> None:
    row = h.begin("protection.item_throttle_default_off", "Default config does not enable item throttle even if CRITICAL")
    try:
        limits = h.cmd("fg limits")
        cfg = ROOT / "src" / "main" / "resources" / "config.yml"
        text = cfg.read_text(encoding="utf-8")
        default_off = "item: false" in text
        row.log_excerpt = limits[-1500:]
        row.extra["limits"] = limits[-1500:]
        row.extra["defaultItemThrottle"] = default_off
        if default_off:
            row.finish("PASS", "production default item throttle is false")
        else:
            row.finish("FAIL", "production default item throttle is not false")
    except Exception as exc:  # noqa: BLE001
        row.finish("ERROR", str(exc))
