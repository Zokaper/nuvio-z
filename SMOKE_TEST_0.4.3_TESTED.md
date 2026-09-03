These are my findings based off of SMOKE_TEST_0.4.3.md , please note that I did not do EVERYTHING it told me to check because I cant be bothered to honestly.

A1: Works, but the selector looks like it was made for mobile on desktop.
A2: Instant works, but it selected a 1080p source on wifi on desktop? Also, the step labels only show the first one (finding source) then immediately goes to the screen that shows the logo of the show as it loads. This is likely because the other steps happen almost instantly, so its fine. HOWEVER, the source selector appears for a second in the background. Not a big deal but ideally a polished look would be tap -> the steps thing -> the player right away. Include loading hte actual source in the loader thing maybe.
A3: Out of testing scope for now, cant be bothered.
A4: Backing out pulls up the source selection. Not ideal for instant/streamlined. I think the root issue here is that the per episode page *is* the source selector, so the app has nothing else to  show when backing out. Either we go to the series page or, idk honestly.
A5: It worked on mobile, seemingly not working on desktop. Maybe the app cant tell if its hotspot like mobile can?
A6: No safe automatic source matched, for every preset. Also, the bitrate caps you got by default are mildly inflated, the 4K sources I use are sometimes 6gb/hr for example. 1080 is usually like 800mb/hr. Bigger issue is automatic source not owrking obv. As for the sticky pin, I cant tell if it oworks (the prompt worked atleast, as in it showed up) because at the moment opening the episode switcher falls back to Classic behaiviour, we didnt account for this. We should add a next epsidoe button to the player aswell.
A7: Play manually works.
A8: Lets leave this for later.

B: goodness gracious im not doing allat, perchance make some kind of automated test, but the mode selector appears to be working fine so lets just hope.

SIDENOTE: wwaaaittt a minute. in settings there is a section called next episode, talking about a "binge group". is that our featuer or is this something in vanilla nuvio that supposedly does what oru feature does?

C: I think it works but more things should fall under advanced. Also advanced settings should eb marked as so to limit confusion.

D: all good.

D2: I believe all good.

E: Untested fully, but holding tap to download a source and clicking download opens up te preset picker. This shouldnt be the behaiviour in ANY mode, it should just download that source if you hold tap on it.

F. seems fine.


Notes: the little icon for opening details instead of the episode for continue watchign isnt there, it might be uncommited locally somewhere.
The location of the mode switcher should be more prominent and not thrown into playback, as it does more than just playback.