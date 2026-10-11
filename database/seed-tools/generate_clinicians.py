#!/usr/bin/env python3
"""Generates backend/src/main/resources/seed/clinicians.json and practices.json (synthetic, deterministic).

100 fictional clinicians (40 physicians, 30 RNs, 15 L&D nurses, 10 CRNAs, 5 anesthesiologists) plus one administrative
demo account. The six original demo accounts keep their IDs. Re-running produces the same files (fixed seed).
Usage: python3 database/seed-tools/generate_clinicians.py
"""
import json
import pathlib
import random

ROOT = pathlib.Path(__file__).resolve().parents[2]
SEED = ROOT / "backend/src/main/resources/seed"
rng = random.Random(2026)

PRACTICES = [
    ("PRAC-A", "Larkspur Regional Primary Care (Practice A)", "LRMC", "Internal Medicine / Primary Care"),
    ("PRAC-B", "Bellwether Women's Health Associates (Practice B)", "BWHA", "Obstetrics & Gynecology / Maternal-Fetal Medicine"),
    ("PRAC-C", "Larkspur Heart & Vascular (Practice C)", "LRMC", "Cardiology"),
    ("PRAC-D", "Cedar Hollow Orthopedics (Practice D)", "CHCH", "Orthopedics"),
    ("PRAC-E", "Cedar Hollow Family Medicine (Practice E)", "CHCH", "Family Medicine"),
    ("PRAC-F", "Larkspur Endocrinology (Practice F)", "LRMC", "Endocrinology"),
    ("PRAC-G", "Larkspur Hematology & Oncology (Practice G)", "LRMC", "Hematology / Oncology"),
    ("PRAC-H", "Cedar Hollow Neurology (Practice H)", "CHCH", "Neurology"),
    ("PRAC-I", "Riverbend Emergency & Urgent Care Physicians (Practice I)", "RUC", "Emergency Medicine"),
    ("PRAC-J", "Larkspur Pulmonary & Sleep (Practice J)", "LRMC", "Pulmonology"),
]
PHYSICIANS_PER_PRACTICE = {"PRAC-A": 6, "PRAC-B": 6, "PRAC-C": 4, "PRAC-D": 4, "PRAC-E": 4, "PRAC-F": 3,
                           "PRAC-G": 3, "PRAC-H": 3, "PRAC-I": 4, "PRAC-J": 3}

FIRST = ["Adaeze", "Bram", "Celia", "Dmitri", "Esme", "Farid", "Greer", "Hollis", "Ines", "Jonah", "Keiko", "Lorcan",
         "Marisol", "Nnamdi", "Odette", "Pavel", "Quinn", "Rosalind", "Sefa", "Tamsin", "Ulises", "Vesna", "Wren",
         "Xiomara", "Yusuf", "Zelda", "Anouk", "Bastian", "Calla", "Desmond", "Elowen", "Ferris", "Galen", "Hana",
         "Idris", "Juniper", "Kofi", "Liesel", "Mateo", "Noor", "Orrin", "Philippa", "Rafferty", "Saoirse", "Tobias",
         "Uma", "Vaughn", "Willa", "Yara", "Zev", "Amara", "Beckett", "Cosima", "Dorian", "Eira", "Florian", "Gemma",
         "Hiro", "Ilse", "Jasper", "Kalani", "Leander", "Mireille", "Niall", "Ottilie", "Perrin", "Rhiannon", "Silas"]
LAST = ["Abernathy", "Balogun", "Castellanos", "Delacroix", "Eriksen", "Fairbanks", "Galloway", "Haddad", "Iwasaki",
        "Jovanovic", "Kowalczyk", "Lindqvist", "Mbeki", "Nakashima", "Okonjo", "Prentiss", "Quenneville", "Rasmussen",
        "Sandoval", "Thorne", "Ustinov", "Valcourt", "Whitlock", "Xu", "Yarrow", "Zeller", "Ashdown", "Brightwater",
        "Coulter", "Dunmore", "Ellingsen", "Fontaine", "Grisham", "Hollander", "Ivers", "Jessup", "Kingsley",
        "Lowell", "Montoya", "Northcott", "Oyelaran", "Pemberton", "Radcliffe", "Stroud", "Tennant", "Underhill",
        "Vasquez", "Winslow", "Yoshida", "Zabala"]

used_names = {"Alex Kim", "Sarah Mitchell", "Jordan Lee", "Taylor Morgan", "Maya Ellison", "Daniel Brooks", "Riley Okafor"}


def name():
    while True:
        n = f"{rng.choice(FIRST)} {rng.choice(LAST)}"
        if n not in used_names:
            used_names.add(n)
            return n


def uid(full):
    return full.lower().replace(" ", ".").replace("'", "")


users = []


def add(id_, display, role, department, org, specialty, credentials, practices=(), demo=False):
    users.append({"id": id_, "name": display, "role": role, "department": department, "orgId": org,
                  "specialty": specialty, "credentials": credentials, "practices": list(practices), "demoAccount": demo})


# Representative demo accounts (shown in the user switcher). IDs are unchanged from earlier versions.
add("alex.kim", "Dr. Alex Kim", "PHYSICIAN", "Internal Medicine — Practice A", "LRMC", "Internal Medicine", "MD", ["PRAC-A"], True)
add("sarah.mitchell", "Dr. Sarah Mitchell", "PHYSICIAN", "Obstetrics & Gynecology — Practice B", "BWHA", "Obstetrics & Gynecology", "MD", ["PRAC-B"], True)
add("jordan.lee", "Jordan Lee, RN", "REGISTERED_NURSE", "Medical-Surgical Nursing", "LRMC", "Medical-Surgical Nursing", "RN", [], True)
add("taylor.morgan", "Taylor Morgan, RN", "LD_NURSE", "Labor & Delivery", "LRMC", "Labor & Delivery Nursing", "RN", [], True)
add("maya.ellison", "Maya Ellison, CRNA", "CRNA", "Anesthesia OB Coordination", "LRMC", "Obstetric Anesthesia", "CRNA", [], True)
add("daniel.brooks", "Dr. Daniel Brooks", "ANESTHESIOLOGIST", "Obstetric Anesthesiology", "LRMC", "Anesthesiology", "MD", [], True)
add("riley.okafor", "Riley Okafor", "DATA_COORDINATOR", "Health Information Management", "LRMC", "Clinical Data Coordination", "RHIA", [], True)

# Physicians: 40 in total across ten practices (two of them are the demo physicians above).
practice_by_id = {p[0]: p for p in PRACTICES}
for pid, count in PHYSICIANS_PER_PRACTICE.items():
    _, pname, org, spec = practice_by_id[pid]
    existing = 1 if pid in ("PRAC-A", "PRAC-B") else 0
    for _ in range(count - existing):
        n = name()
        add(uid(n), f"Dr. {n}", "PHYSICIAN", f"{spec} — {pname.split(' (')[-1].rstrip(')')}", org, spec,
            rng.choice(["MD", "DO"]), [pid])

RN_UNITS = [("Medical-Surgical Nursing", "LRMC"), ("Intensive Care Unit", "LRMC"), ("Emergency Department", "LRMC"),
            ("Telemetry", "LRMC"), ("Oncology Nursing", "LRMC"), ("Medical-Surgical Nursing", "CHCH"),
            ("Orthopedic Surgical Unit", "CHCH"), ("Emergency Department", "CHCH")]
for i in range(29):                                    # + jordan.lee = 30
    unit, org = RN_UNITS[i % len(RN_UNITS)]
    n = name()
    add(uid(n), f"{n}, RN", "REGISTERED_NURSE", unit, org, unit, "RN")
for i in range(14):                                    # + taylor.morgan = 15
    unit, org = [("Labor & Delivery", "LRMC"), ("Birthing Center", "CHCH")][i % 2]
    n = name()
    add(uid(n), f"{n}, RN", "LD_NURSE", unit, org, "Labor & Delivery Nursing", "RN")
for i in range(9):                                     # + maya.ellison = 10
    n = name()
    add(uid(n), f"{n}, CRNA", "CRNA", ["Anesthesia — Main OR", "Anesthesia OB Coordination"][i % 2],
        ["LRMC", "CHCH"][i % 2], "Nurse Anesthesia", "CRNA")
for i in range(4):                                     # + daniel.brooks = 5
    n = name()
    add(uid(n), f"Dr. {n}", "ANESTHESIOLOGIST", ["Obstetric Anesthesiology", "General Anesthesiology"][i % 2],
        ["LRMC", "CHCH"][i % 2], "Anesthesiology", "MD")

clinical = [u for u in users if u["role"] != "DATA_COORDINATOR"]
counts = {r: sum(1 for u in clinical if u["role"] == r) for r in
          ("PHYSICIAN", "REGISTERED_NURSE", "LD_NURSE", "CRNA", "ANESTHESIOLOGIST")}
assert len(clinical) == 100 and counts == {"PHYSICIAN": 40, "REGISTERED_NURSE": 30, "LD_NURSE": 15, "CRNA": 10,
                                            "ANESTHESIOLOGIST": 5}, counts
assert len({u["id"] for u in users}) == len(users)

(SEED / "clinicians.json").write_text(json.dumps(users, indent=2, ensure_ascii=False) + "\n")
(SEED / "practices.json").write_text(json.dumps(
    [{"practiceId": p, "name": n, "orgId": o, "specialty": s} for p, n, o, s in PRACTICES], indent=2, ensure_ascii=False) + "\n")
print(f"{len(users)} identities ({len(clinical)} clinicians: {counts}) and {len(PRACTICES)} practices written")
