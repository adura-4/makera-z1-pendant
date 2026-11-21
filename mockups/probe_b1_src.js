
const BL='#38BDF8', ST='#94a3b8', FILL='#334155', DARK='#0B1120', TOP='#475569', LFACE='#334155', RFACE='#253041', WALL1='#2b3648', WALL2='#222c3c';
let uid=0;
const svg=(s,body,id)=>'<svg width="'+s+'" height="'+s+'" viewBox="0 0 60 60"><defs><marker id="'+id+'" markerUnits="userSpaceOnUse" markerWidth="7" markerHeight="7" refX="6.5" refY="3.5" orient="auto"><path d="M0,0 L7,3.5 L0,7 z" fill="'+BL+'"/></marker></defs>'+body+'</svg>';
const ar=(id,x1,y1,x2,y2)=>'<line x1="'+x1+'" y1="'+y1+'" x2="'+x2+'" y2="'+y2+'" stroke="'+BL+'" stroke-width="2" marker-end="url(#'+id+')"/>';
const cross=(x,y,k=1)=>'<g stroke="'+BL+'" stroke-width="1.5" fill="none"><circle cx="'+x+'" cy="'+y+'" r="'+(3.4*k)+'"/><line x1="'+(x-6.5*k)+'" y1="'+y+'" x2="'+(x+6.5*k)+'" y2="'+y+'"/><line x1="'+x+'" y1="'+(y-6.5*k)+'" x2="'+x+'" y2="'+(y+6.5*k)+'"/></g><circle cx="'+x+'" cy="'+y+'" r="1.2" fill="'+BL+'"/>';
const ball=(x,y)=>'<circle cx="'+x+'" cy="'+y+'" r="3.2" fill="rgba(56,189,248,.35)" stroke="'+BL+'" stroke-width="1.4"/>';
const trail=(x1,y1,x2,y2)=>'<line x1="'+x1+'" y1="'+y1+'" x2="'+x2+'" y2="'+y2+'" stroke="'+BL+'" stroke-width="1.3" stroke-dasharray="2 2" opacity=".75"/>';
const MIR={bl:'',tl:'translate(0,60) scale(1,-1)',br:'translate(60,0) scale(-1,1)',tr:'translate(60,60) scale(-1,-1)'};
const g=(c,b)=>'<g transform="'+MIR[c]+'">'+b+'</g>';

// ---------- top-view styles (A arrows, B balls) ----------
function top(style, kind, sub, size){
  const id='m'+(uid++); let b='';
  const A=style==='A';
  if(kind==='out'){            // front-left drawn, mirrored for the others
    let t='<rect x="22" y="4" width="34" height="34" rx="1" fill="'+FILL+'" stroke="'+ST+'" stroke-width="1.4"/>';
    t+= A ? ar(id,6,25,22,25)+ar(id,35,54,35,38)
          : trail(6,25,14,25)+ball(18.8,25)+trail(35,54,35,46)+ball(35,41.2);
    // crosshair drawn unmirrored so it stays symmetric
    b=g(sub,t)+cross(...pt(sub,22,38));
  } else if(kind==='in'){
    let t='<rect x="4" y="4" width="52" height="52" rx="2" fill="'+FILL+'" stroke="'+ST+'" stroke-width="1.2"/><rect x="22" y="10" width="28" height="28" fill="'+DARK+'" stroke="'+ST+'" stroke-width="1.4"/>';
    t+= A ? ar(id,35,27,22,27)+ar(id,40,23,40,38)
          : trail(36,27,29,27)+ball(25.2,27)+trail(40,16,40,31)+ball(40,34.8);
    b=g(sub,t)+cross(...pt(sub,22,38));
  } else if(kind==='hole'){
    b='<rect x="4" y="4" width="52" height="52" rx="2" fill="'+FILL+'" stroke="'+ST+'" stroke-width="1.2"/>'+
      (sub==='sq' ? '<rect x="12" y="12" width="36" height="36" fill="'+DARK+'" stroke="'+ST+'" stroke-width="1.4"/>'
                  : '<circle cx="30" cy="30" r="18" fill="'+DARK+'" stroke="'+ST+'" stroke-width="1.4"/>');
    b+= A ? ar(id,37,30,48,30)+ar(id,23,30,12,30)+ar(id,30,23,30,12)+ar(id,30,37,30,48)
          : ball(44.8,30)+ball(15.2,30)+ball(30,15.2)+ball(30,44.8);
    b+=cross(30,30,.7);
  } else if(kind==='block'){
    b= sub==='sq' ? '<rect x="17" y="17" width="26" height="26" rx="1" fill="'+FILL+'" stroke="'+ST+'" stroke-width="1.4"/>'
                  : '<circle cx="30" cy="30" r="13" fill="'+FILL+'" stroke="'+ST+'" stroke-width="1.4"/>';
    b+= A ? ar(id,2,30,17,30)+ar(id,58,30,43,30)+ar(id,30,2,30,17)+ar(id,30,58,30,43)
          : trail(2,30,9,30)+ball(13.8,30)+trail(58,30,51,30)+ball(46.2,30)+trail(30,2,30,9)+ball(30,13.8)+trail(30,58,30,51)+ball(30,46.2);
    b+=cross(30,30,.7);
  } else if(kind==='z'){       // side view
    b='<rect x="8" y="34" width="44" height="20" rx="1.5" fill="'+FILL+'" stroke="'+ST+'" stroke-width="1.4"/>';
    b+= A ? ar(id,30,6,30,34) : trail(30,6,30,26)+ball(30,30.8);
    b+='<line x1="4" y1="34" x2="56" y2="34" stroke="'+BL+'" stroke-width="1.6" stroke-dasharray="3 2"/><text x="56" y="30" fill="'+BL+'" font-size="8" font-family="Roboto,Arial" text-anchor="end" font-weight="700">Z0</text>';
  }
  return svg(size,b,id);
}
function pt(c,x,y){ return c==='bl'?[x,y]:c==='tl'?[x,60-y]:c==='br'?[60-x,y]:[60-x,60-y]; }

// ---------- isometric styles (C arrows, D zero only) ----------
function iso(style, kind, sub, size){
  const id='m'+(uid++), arrows=false;
  let o=[30,52,1.25];
  const P=(x,y,z)=>[o[0]+(x-y)*0.866*o[2], o[1]-(x+y)*0.5*o[2]-z*o[2]];
  const poly=(pts,fill,extra)=>'<polygon points="'+pts.map(p=>P(...p).map(v=>v.toFixed(2)).join(',')).join(' ')+'" fill="'+fill+'" stroke="'+ST+'" stroke-width="0.8" stroke-linejoin="round" '+(extra||'')+'/>';
  const path=(rings)=>rings.map(r=>'M'+r.map(p=>P(...p).map(v=>v.toFixed(2)).join(',')).join(' L')+' Z').join(' ');
  const box=(x0,y0,z0,w,d,h,topExtra)=>
    poly([[x0,y0,z0],[x0,y0+d,z0],[x0,y0+d,z0+h],[x0,y0,z0+h]],LFACE)+
    poly([[x0,y0,z0],[x0+w,y0,z0],[x0+w,y0,z0+h],[x0,y0,z0+h]],RFACE)+
    (topExtra===undefined ? poly([[x0,y0,z0+h],[x0+w,y0,z0+h],[x0+w,y0+d,z0+h],[x0,y0+d,z0+h]],TOP) : '');
  const circ=(cx,cy,z,r,a0=0,a1=360,n=48)=>{const a=[];for(let i=0;i<=n;i++){const t=(a0+(a1-a0)*i/n)*Math.PI/180;a.push([cx+r*Math.cos(t),cy+r*Math.sin(t),z]);}return a;};
  const sq=(cx,cy,z,h)=>[[cx-h,cy-h,z],[cx+h,cy-h,z],[cx+h,cy+h,z],[cx-h,cy+h,z]];
  const ia=(a,b)=>{const p=P(...a),q=P(...b);return ar(id,p[0].toFixed(2),p[1].toFixed(2),q[0].toFixed(2),q[1].toFixed(2));};
  const pts=a=>a.map(p=>P(...p).map(v=>v.toFixed(2)).join(',')).join(' ');
  const seg=(a,b,w,extra)=>{const p=P(...a),q=P(...b);return '<line x1="'+p[0].toFixed(2)+'" y1="'+p[1].toFixed(2)+'" x2="'+q[0].toFixed(2)+'" y2="'+q[1].toFixed(2)+'" stroke="'+BL+'" stroke-width="'+w+'" stroke-linecap="round" '+(extra||'')+'/>';};
  const zero=(x,y,z,ax='xyz')=>{
    if(style==='D1'){          // flat target lying on the surface
      return '<polygon points="'+pts(circ(x,y,z,3.3,0,360,40))+'" fill="rgba(11,17,32,.55)" stroke="'+BL+'" stroke-width="1.7"/>'+
             '<polygon points="'+pts(circ(x,y,z,1.3,0,360,24))+'" fill="'+BL+'"/>';
    }
    if(style==='D2'){          // flat crosshair: ring + lines along the part's X and Y
      return '<polygon points="'+pts(circ(x,y,z,2.6,0,360,40))+'" fill="none" stroke="'+BL+'" stroke-width="1.5"/>'+
             seg([x-5.5,y,z],[x+5.5,y,z],1.4)+seg([x,y-5.5,z],[x,y+5.5,z],1.4)+
             '<polygon points="'+pts(circ(x,y,z,0.9,0,360,16))+'" fill="'+BL+'"/>';
    }
    // D3: axis triad X / Y / Z from the zero point
    const L=8, hd=(a,b)=>{const p=P(...a),q=P(...b);const dx=q[0]-p[0],dy=q[1]-p[1],n=Math.hypot(dx,dy),ux=dx/n,uy=dy/n;
      const bx=q[0]-ux*3.6,by=q[1]-uy*3.6;
      return '<line x1="'+p[0].toFixed(2)+'" y1="'+p[1].toFixed(2)+'" x2="'+bx.toFixed(2)+'" y2="'+by.toFixed(2)+'" stroke="'+BL+'" stroke-width="1.4"/>'+
        '<polygon points="'+q[0].toFixed(2)+','+q[1].toFixed(2)+' '+(bx-uy*1.7).toFixed(2)+','+(by+ux*1.7).toFixed(2)+' '+(bx+uy*1.7).toFixed(2)+','+(by-ux*1.7).toFixed(2)+'" fill="'+BL+'"/>';};
    const lab=(q,t)=>{const c=P(...q);return '<text x="'+c[0].toFixed(1)+'" y="'+(c[1]+2.4).toFixed(1)+'" fill="'+BL+'" font-size="6.5" font-weight="700" font-family="Roboto,Arial" text-anchor="middle">'+t+'</text>';};
    return (ax.includes('x')?hd([x,y,z],[x+L,y,z])+lab([x+L+3.2,y,z],'X'):'')+(ax.includes('y')?hd([x,y,z],[x,y+L,z])+lab([x,y+L+3.2,z],'Y'):'')+
           (ax.includes('z')?hd([x,y,z],[x,y,z+L])+lab([x,y,z+L+3.4],'Z'):'')+
           '<circle cx="'+P(x,y,z)[0].toFixed(2)+'" cy="'+P(x,y,z)[1].toFixed(2)+'" r="1.5" fill="'+BL+'"/>';
  };
  let b='';
  if(kind==='out'){
    o=[30,47,1.55];
    b=box(0,0,0,17,17,7);
    if(arrows) b+=ia([-9,9,4.5],[0,9,4.5])+ia([9,-9,4.5],[9,0,4.5]);
    b+=zero(0,0,7);
  } else if(kind==='in'){
    o=[30,51,1.42]; const H=7, x0=6,x1=18, d=5;
    const op=[[x0,x0,H],[x1,x0,H],[x1,x1,H],[x0,x1,H]];
    b=box(0,0,0,21,21,H,false)+'<clipPath id="c'+id+'"><path d="'+path([op])+'"/></clipPath><g clip-path="url(#c'+id+')">'+
      poly([[x1,x0,H-d],[x1,x1,H-d],[x1,x1,H],[x1,x0,H]],WALL1)+poly([[x0,x1,H-d],[x1,x1,H-d],[x1,x1,H],[x0,x1,H]],WALL2)+
      poly([[x0,x0,H-d],[x1,x0,H-d],[x1,x1,H-d],[x0,x1,H-d]],DARK)+'</g>'+
      '<path d="'+path([[[0,0,H],[21,0,H],[21,21,H],[0,21,H]],op])+'" fill="'+TOP+'" fill-rule="evenodd" stroke="'+ST+'" stroke-width="0.8"/>';
    if(arrows) b+=ia([11,9.5,H-2.5],[x1,9.5,H-2.5])+ia([9.5,11,H-2.5],[9.5,x1,H-2.5]);
    b+=zero(x0,x0,H);
  } else if(kind==='hole'){
    o=[30,52,1.4]; const H=7, c=11, r=7, d=8;
    const op= sub==='sq'? sq(c,c,H,r) : circ(c,c,H,r);
    const fl= sub==='sq'? sq(c,c,H-d,r) : circ(c,c,H-d,r);
    b=box(0,0,0,22,22,H,false)+'<clipPath id="c'+id+'"><path d="'+path([op])+'"/></clipPath><g clip-path="url(#c'+id+')"><path d="'+path([op])+'" fill="'+WALL1+'"/><path d="'+path([fl])+'" fill="'+DARK+'"/></g>'+
      '<path d="'+path([[[0,0,H],[22,0,H],[22,22,H],[0,22,H]],op])+'" fill="'+TOP+'" fill-rule="evenodd" stroke="'+ST+'" stroke-width="0.8"/>';
    const z=H-3;
    if(arrows) b+=ia([c+2.5,c-1.5,z],[c+r,c-1.5,z])+ia([c-1.5,c+2.5,z],[c-1.5,c+r,z]);
    b+=zero(c,c,H,'xy');
  } else if(kind==='block'){
    o=[30,53,1.2]; const c=13, r=6, z0=3, h=8;
    b=box(0,0,0,26,26,z0);
    if(sub==='sq') b+=box(c-r,c-r,z0,2*r,2*r,h);
    else{
      const side=circ(c,c,z0,r,135,315).concat(circ(c,c,z0+h,r,315,135));
      b+=poly(side,LFACE)+poly(circ(c,c,z0+h,r),TOP);
    }
    const z=z0+h-2.5;
    if(arrows) b+=ia([c-r-8,c,z],[c-r,c,z])+ia([c,c-r-8,z],[c,c-r,z]);
    b+=zero(c,c,z0+h);
  } else if(kind==='z'){
    o=[30,52,1.45];
    b=box(0,0,0,20,20,6);
    if(arrows) b+=ia([10,10,22],[10,10,6]);
    b+=zero(10,10,6,'z');
  }
  return svg(size,b,id);
}

const STY=[
  ['D1','Flat target','A ring and dot drawn lying on the surface, so it tilts with the part.',iso],
  ['D2','Flat crosshair','Ring plus lines running along the part’s X and Y edges.',iso],
  ['D3','Axis arrows','X, Y and Z arrows coming out of the zero point, like a CAM origin.',iso],
];
const TILES=[
  ['out','Outside corner','Zero: X Y Z'],
  ['in','Inside corner','Zero: X Y Z'],
  ['hole','Hole center','round or square · Zero: X Y (not Z)'],
  ['block','Block center','round or square · Zero: X Y Z'],
  ['z','Top only','Zero: Z'],
];
document.getElementById('row').innerHTML=STY.map(([k,name,desc,fn])=>{
  const ic=(kind,sub,s)=>fn(k,kind,sub,s);
  const tiles=TILES.map((t,i)=>{
    const pic = (t[0]==='hole'||t[0]==='block') ? '<div class="pair">'+ic(t[0],'rd',78)+ic(t[0],'sq',78)+'</div>' : ic(t[0],'bl',88);
    return '<div class="tt'+(i===0?' on':'')+'">'+pic+'<span>'+t[1]+'</span><small>'+t[2]+'</small></div>';
  }).join('');
  const corners=[['tl','back-left'],['tr','back-right'],['bl','front-left'],['br','front-right']]
    .map(c=>'<div class="tt'+(c[0]==='bl'?' on':'')+'">'+top('A','out',c[0],54).replace(/<line[^>]*marker-end[^>]*\/>/g,'')+'<span>'+c[1]+'</span></div>').join('');
  return '<div class="col"><div class="cap">'+k+' · '+name+'</div><div class="capd">'+desc+'</div><div class="panel">'+
    '<div class="sub2">What</div><div class="grid">'+tiles+'</div>'+
    '<div class="sub2">Which corner? (seen from above, front = toward you)</div><div class="corners">'+corners+'</div></div></div>';
}).join('');
